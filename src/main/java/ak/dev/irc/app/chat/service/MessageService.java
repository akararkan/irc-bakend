package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MediaRef;
import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.dto.request.MediaRefDto;
import ak.dev.irc.app.chat.dto.request.SendMessageRequest;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import ak.dev.irc.app.chat.dto.response.ReactionSummary;
import ak.dev.irc.app.chat.dto.response.ReplyPreview;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.entity.MessageRequest;
import ak.dev.irc.app.chat.enums.*;
import ak.dev.irc.app.chat.mapper.ChatMapper;
import ak.dev.irc.app.chat.permission.ChatPermissionEngine;
import ak.dev.irc.app.chat.permission.ChatRelationshipService;
import ak.dev.irc.app.chat.permission.GroupPermissions;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.entity.ConversationPin;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationPinRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.repository.MessageRequestRepository;
import ak.dev.irc.app.chat.search.service.ChatSearchService;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.util.MentionExtractor;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The message hot path: send (idempotent, permission-gated, single-partition
 * Cassandra write + a couple of indexed Postgres updates + non-blocking realtime
 * publish), plus edit, soft-delete, reactions, forward, and delivered receipts.
 * No scan, no join, no lock on the write path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /** Eager per-member unread fan-out is fine up to here; above it we go lazy. */
    private static final int LARGE_GROUP_CUTOFF = 256;
    /** Messages a stranger may send into an unaccepted thread before it's blocked. */
    private static final int STRANGER_MESSAGE_CAP = 3;

    private final SnowflakeIdGenerator snowflake;
    private final MessageByConversationRepository messageRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final MessageRequestRepository messageRequestRepo;
    private final ChatPermissionEngine permissionEngine;
    private final ChatRelationshipService relationships;
    private final ChatIdempotencyService idempotency;
    private final ReactionService reactionService;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatNotificationService chatNotifications;
    private final PresenceService presence;
    private final UnreadBadgeCache unreadBadge;
    private final ChatMapper mapper;
    private final UserRepository userRepository;
    private final S3StorageService storageService;
    private final RateLimiter rateLimiter;
    private final ConversationPinRepository pinRepo;
    private final SystemMessageService systemMessages;
    private final ChatSearchService chatSearch;

    // ── SEND ──────────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse send(UUID conversationId, UUID senderId, SendMessageRequest req) {
        rateLimiter.check("chat-send", senderId, 30, Duration.ofSeconds(10));

        Conversation convo = conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));

        ConversationMember senderMember = memberRepo.findMember(conversationId, senderId)
                .orElseThrow(() -> new ForbiddenException(
                        "You are not a member of this conversation.", "NOT_A_MEMBER"));

        // Idempotency: mint the id, then claim the nonce. A retry that lost the
        // race returns the already-created message instead of a second row.
        long messageId = snowflake.nextId();
        if (!idempotency.claim(senderId, req.getClientNonce(), messageId)) {
            Long existing = idempotency.existingMessageId(senderId, req.getClientNonce());
            if (existing != null) return echoExisting(existing, senderId, conversationId, req);
        }

        try {
            // Permission — group vs direct.
            SendDecision decision = SendDecision.ALLOW;
            UUID directPeer = null;
            if (convo.isGroup()) {
                authorizeGroupSend(senderMember, convo);
            } else {
                directPeer = otherDirectMember(conversationId, senderId);
                decision = permissionEngine.authorizeDirectSend(senderId, directPeer);
                if (decision == SendDecision.DENY) {
                    throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
                }
            }

            // Stranger first contact → ensure request row + enforce the pre-accept cap.
            boolean requestJustCreated = false;
            MessageRequest request = null;
            if (decision == SendDecision.ROUTE_TO_REQUEST) {
                RequestOutcome ro = ensureRequestAndCap(convo, senderId, directPeer, messageId);
                request = ro.request();
                requestJustCreated = ro.justCreated();
            }

            String type = req.getType() != null ? req.getType().name() : MessageType.TEXT.name();
            List<MediaRef> media = buildMedia(req.getMedia());
            Set<UUID> mentions = resolveMentions(req.getBody());

            MessageResponse response = persist(convo, senderId, messageId, type,
                    req.getBody(), media, mentions, req.getReplyToId(), null);

            dispatch(convo, senderId, directPeer, decision, request, requestJustCreated,
                    response, previewOf(req.getBody(), type, media));

            return response;
        } catch (RuntimeException e) {
            // Freed so a legitimate retry re-attempts rather than echoing a message
            // that was never written (send rejected before persistence).
            idempotency.release(senderId, req.getClientNonce());
            throw e;
        }
    }

    // ── FORWARD ─────────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse forward(long sourceMessageId, UUID senderId, UUID targetConversationId, String nonce) {
        MessageByIdEntity src = messageByIdRepo.findById(sourceMessageId)
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", sourceMessageId));

        // Must be able to read the source.
        memberRepo.findMember(src.getConversationId(), senderId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You cannot access the source message.", "NOT_A_MEMBER"));

        Conversation target = conversationRepo.findById(targetConversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", targetConversationId));
        ConversationMember targetMember = memberRepo.findMember(targetConversationId, senderId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of the target.", "NOT_A_MEMBER"));

        SendDecision decision = SendDecision.ALLOW;
        UUID directPeer = null;
        if (target.isGroup()) {
            authorizeGroupSend(targetMember, target);
        } else {
            directPeer = otherDirectMember(targetConversationId, senderId);
            decision = permissionEngine.authorizeDirectSend(senderId, directPeer);
            if (decision == SendDecision.DENY) {
                throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
            }
        }

        long messageId = snowflake.nextId();
        if (!idempotency.claim(senderId, nonce, messageId)) {
            Long existing = idempotency.existingMessageId(senderId, nonce);
            if (existing != null) return mapById(existing, senderId);
        }

        try {
            boolean requestJustCreated = false;
            MessageRequest request = null;
            if (decision == SendDecision.ROUTE_TO_REQUEST) {
                RequestOutcome ro = ensureRequestAndCap(target, senderId, directPeer, messageId);
                request = ro.request();
                requestJustCreated = ro.justCreated();
            }

            MessageResponse response = persist(target, senderId, messageId, src.getType(),
                    src.getBody(), src.getMedia(), null, null, src.getConversationId());

            dispatch(target, senderId, directPeer, decision, request, requestJustCreated,
                    response, previewOf(src.getBody(), src.getType(), src.getMedia()));
            return response;
        } catch (RuntimeException e) {
            idempotency.release(senderId, nonce);
            throw e;
        }
    }

    // ── EDIT ─────────────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse edit(long messageId, UUID userId, String body) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        if (Boolean.TRUE.equals(m.getDeleted())) {
            throw new BadRequestException("Cannot edit a deleted message.");
        }
        if (MessageType.SYSTEM.name().equals(m.getType())) {
            throw new BadRequestException("System messages cannot be edited.");
        }
        if (!userId.equals(m.getSenderId())) {
            throw new ForbiddenException("You can only edit your own messages.", "ACCESS_FORBIDDEN");
        }
        Instant now = Instant.now();
        messageRepo.editBody(m.getConversationId(), m.getBucket(), messageId, body, now);
        messageByIdRepo.editBody(messageId, body, now);
        m.setBody(body);
        m.setEditedAt(now);
        chatSearch.indexAsync(m); // re-index the edited body

        List<UUID> recipients = memberRepo.findReadableMemberIds(m.getConversationId());
        broadcaster.broadcast(recipients, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MESSAGE_EDITED)
                .conversationId(m.getConversationId())
                .messageId(messageId).body(body).editedAt(now)
                .build());
        return mapById(messageId, userId);
    }

    // ── DELETE (soft) ─────────────────────────────────────────────────────────────

    @Transactional
    public void delete(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        if (Boolean.TRUE.equals(m.getDeleted())) return; // idempotent

        boolean own = userId.equals(m.getSenderId());
        if (!own) {
            // Group admins/owners may delete anyone's message.
            Conversation convo = conversationRepo.findById(m.getConversationId()).orElse(null);
            ConversationMember me = memberRepo.findMember(m.getConversationId(), userId).orElse(null);
            boolean adminDelete = convo != null && convo.isGroup() && me != null && me.isActive()
                    && GroupPermissions.can(me.getRole(), GroupAction.DELETE_ANY_MESSAGE, null, convo.getGroupSettings());
            if (!adminDelete) {
                throw new ForbiddenException("You cannot delete this message.", "ACCESS_FORBIDDEN");
            }
        }
        messageRepo.tombstone(m.getConversationId(), m.getBucket(), messageId);
        messageByIdRepo.tombstone(messageId);
        reactionService.clear(messageId);
        chatSearch.deleteAsync(messageId);
        pinRepo.deletePin(m.getConversationId(), messageId); // a deleted message can't stay pinned

        List<UUID> recipients = memberRepo.findReadableMemberIds(m.getConversationId());
        broadcaster.broadcast(recipients, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MESSAGE_DELETED)
                .conversationId(m.getConversationId())
                .messageId(messageId)
                .build());
    }

    // ── REACTIONS ──────────────────────────────────────────────────────────────────

    @Transactional
    public List<ReactionSummary> react(long messageId, UUID userId, String emoji) {
        MessageByIdEntity m = requirePostableMessage(messageId, userId);
        boolean changed = reactionService.react(messageId, userId, emoji);
        if (changed) {
            broadcastReaction(m.getConversationId(), messageId, userId, emoji, true);
        }
        return reactionService.detailFor(messageId, userId);
    }

    @Transactional
    public List<ReactionSummary> unreact(long messageId, UUID userId) {
        MessageByIdEntity m = requirePostableMessage(messageId, userId);
        String removed = reactionService.unreact(messageId, userId);
        if (removed != null) {
            broadcastReaction(m.getConversationId(), messageId, userId, removed, false);
        }
        return reactionService.detailFor(messageId, userId);
    }

    // ── DELIVERED RECEIPT ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void markDelivered(long messageId, UUID userId) {
        MessageByIdEntity m = requireReadableMessage(messageId, userId);
        // Don't leak presence/receipts on a pending request or a restricted thread.
        if (relationships.suppressEphemeral(m.getConversationId(), userId)) return;
        List<UUID> recipients = memberRepo.findActiveMemberIds(m.getConversationId());
        broadcaster.broadcastExcept(recipients, userId, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.RECEIPT_DELIVERED)
                .conversationId(m.getConversationId())
                .messageId(messageId).userId(userId)
                .build());
    }

    // ── PIN / UNPIN ──────────────────────────────────────────────────────────────────

    @Transactional
    public void pinMessage(UUID conversationId, long messageId, UUID userId) {
        Conversation convo = requirePinnable(conversationId, userId);
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .filter(x -> conversationId.equals(x.getConversationId()) && !Boolean.TRUE.equals(x.getDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        if (pinRepo.findByConversationIdAndMessageId(conversationId, messageId).isPresent()) return; // idempotent
        pinRepo.save(ConversationPin.builder()
                .conversationId(conversationId).messageId(m.getMessageId()).pinnedBy(userId)
                .build());
        systemMessages.write(conversationId, SystemEventType.PINNED, userId, senderLabel(userId) + " pinned a message");
        broadcaster.broadcast(memberRepo.findReadableMemberIds(conversationId), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                .conversationId(conversationId).messageId(messageId).memberChange("PINNED")
                .build());
    }

    @Transactional
    public void unpinMessage(UUID conversationId, long messageId, UUID userId) {
        requirePinnable(conversationId, userId);
        if (pinRepo.deletePin(conversationId, messageId) > 0) {
            broadcaster.broadcast(memberRepo.findReadableMemberIds(conversationId), ChatRealtimeEvent.builder()
                    .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                    .conversationId(conversationId).messageId(messageId).memberChange("UNPINNED")
                    .build());
        }
    }

    /** Active membership + (for groups) the {@code whoCanPin} permission. */
    private Conversation requirePinnable(UUID conversationId, UUID userId) {
        Conversation convo = conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        ConversationMember me = memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException("You are not an active member of this conversation.", "NOT_A_MEMBER"));
        if (convo.isGroup()
                && !GroupPermissions.can(me.getRole(), GroupAction.PIN_MESSAGE, null, convo.getGroupSettings())) {
            throw new ForbiddenException("You cannot pin messages in this group.", "ADMINS_ONLY");
        }
        return convo;
    }

    // ── internals ────────────────────────────────────────────────────────────────────

    /** Writes the twin Cassandra rows and advances the inbox pointer. Returns the mapped response. */
    private MessageResponse persist(Conversation convo, UUID senderId, long messageId, String type,
                                    String body, List<MediaRef> media, Set<UUID> mentions,
                                    Long replyToId, UUID forwardedFrom) {
        int bucket = ChatBuckets.bucketOf(messageId);
        Instant now = Instant.now();

        MessageByConversationEntity row = MessageByConversationEntity.builder()
                .conversationId(convo.getId()).bucket(bucket).messageId(messageId)
                .senderId(senderId).type(type).body(emptyToNull(body))
                .media(media == null || media.isEmpty() ? null : media)
                .replyToId(replyToId).forwardedFrom(forwardedFrom)
                .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
                .deleted(false).createdAt(now)
                .build();
        messageRepo.save(row);
        MessageByIdEntity byId = MessageByIdEntity.builder()
                .messageId(messageId).conversationId(convo.getId()).bucket(bucket)
                .senderId(senderId).type(type).body(emptyToNull(body))
                .media(media == null || media.isEmpty() ? null : media)
                .replyToId(replyToId).forwardedFrom(forwardedFrom)
                .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
                .deleted(false).createdAt(now)
                .build();
        messageByIdRepo.save(byId);

        conversationRepo.advanceLastMessage(convo.getId(), messageId,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), previewOf(body, type, media));

        // The sender has, by definition, "read" their own message — advance their
        // own marker so they're never shown as unread on their own latest message
        // (and hasUnread stays false for them). Covers send and forward.
        memberRepo.advanceOwnMarker(convo.getId(), senderId, messageId);
        unreadBadge.invalidate(senderId);

        // Index for full-text search (async, best-effort — never blocks the send).
        chatSearch.indexAsync(byId);

        Map<UUID, User> users = loadUsers(Set.of(senderId));
        ReplyPreview replyPreview = replyToId == null ? null
                : mapper.toReplyPreview(messageByIdRepo.findById(replyToId).orElse(null));
        return mapper.toMessage(row, users, List.of(), replyPreview);
    }

    /** Unread fan-out, realtime broadcast, and offline notifications. */
    private void dispatch(Conversation convo, UUID senderId, UUID directPeer, SendDecision decision,
                          MessageRequest request, boolean requestJustCreated,
                          MessageResponse response, String preview) {
        UUID conversationId = convo.getId();

        // Recipients for realtime + unread. Readable members (ACTIVE + RESTRICTED)
        // so a read-only group member still receives new messages, edits, deletes,
        // reactions and unread consistently.
        List<UUID> recipients = (decision == SendDecision.ROUTE_TO_REQUEST
                || decision == SendDecision.DELIVER_RESTRICTED)
                ? new ArrayList<>(List.of(directPeer))
                : memberRepo.findReadableMemberIds(conversationId);

        // Eager unread fan-out only for small conversations, and only for a
        // normally-delivered message — a hidden request or a muted restricted
        // message must not inflate the recipient's badge.
        boolean smallEnough = convo.getMemberCount() <= LARGE_GROUP_CUTOFF;
        if (smallEnough && decision == SendDecision.ALLOW) {
            memberRepo.bumpUnreadForOthers(conversationId, senderId);
        }

        // Realtime message.new.
        ChatRealtimeEvent newEvt = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MESSAGE_NEW)
                .conversationId(conversationId).message(response)
                .build();
        if (decision == SendDecision.ROUTE_TO_REQUEST) {
            // The peer (into their Requests tray) + the sender's own other devices.
            broadcaster.broadcast(List.of(directPeer, senderId), newEvt);
            if (request != null && requestJustCreated) {
                User requester = userRepository.findById(senderId).orElse(null);
                broadcaster.broadcastTo(directPeer, ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.REQUEST_NEW)
                        .conversationId(conversationId)
                        .request(mapper.toMessageRequest(request, requester))
                        .build());
            }
        } else if (decision == SendDecision.DELIVER_RESTRICTED) {
            // The peer's restricted tray + the sender's own devices (the sender
            // never learns they were restricted).
            broadcaster.broadcast(List.of(directPeer, senderId), newEvt);
        } else {
            // Deliver to all members INCLUDING the sender's other devices (clients
            // dedupe by messageId), so multi-device stays in sync.
            broadcaster.broadcast(recipients, newEvt);
        }

        // Offline bell notifications (skip restricted; request → one MESSAGE_REQUEST).
        if (decision == SendDecision.ROUTE_TO_REQUEST) {
            if (requestJustCreated) {
                chatNotifications.notifyMessageRequest(directPeer, senderId, conversationId, senderLabel(senderId));
            }
        } else if (decision == SendDecision.ALLOW && smallEnough) {
            String label = senderLabel(senderId);
            for (UUID r : recipients) {
                if (r.equals(senderId)) continue;
                unreadBadge.invalidate(r);
                if (!presence.isOnline(r)) {
                    chatNotifications.notifyNewMessage(r, senderId, conversationId, label, preview);
                }
            }
        }
    }

    private void authorizeGroupSend(ConversationMember member, Conversation convo) {
        if (member.getStatus() == MemberStatus.LEFT || member.getStatus() == MemberStatus.REMOVED) {
            throw new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER");
        }
        if (member.getStatus() == MemberStatus.RESTRICTED) {
            throw new ForbiddenException("You are restricted from posting here.", "READ_ONLY");
        }
        if (!GroupPermissions.can(member.getRole(), GroupAction.SEND_MESSAGE, null, convo.getGroupSettings())) {
            throw new ForbiddenException("Only admins can send messages here.", "ADMINS_ONLY");
        }
    }

    private RequestOutcome ensureRequestAndCap(Conversation convo, UUID senderId, UUID peerId, long messageId) {
        MessageRequest existing = messageRequestRepo.findByConversationId(convo.getId()).orElse(null);
        if (existing == null) {
            MessageRequest r = MessageRequest.builder()
                    .conversationId(convo.getId())
                    .requesterId(senderId).recipientId(peerId)
                    .status(MessageRequestStatus.PENDING)
                    .firstMessageId(messageId).messageCount(1)
                    .build();
            return new RequestOutcome(messageRequestRepo.save(r), true);
        }
        if (existing.getStatus() != MessageRequestStatus.PENDING) {
            // Declined/blocked (accepted would not route here) — refuse further sends.
            throw new ForbiddenException("This interaction is not allowed.", "REQUEST_LIMIT_REACHED");
        }
        if (existing.getMessageCount() >= STRANGER_MESSAGE_CAP) {
            throw new ForbiddenException(
                    "You've reached the limit before this request is accepted.", "REQUEST_LIMIT_REACHED");
        }
        existing.setMessageCount(existing.getMessageCount() + 1);
        return new RequestOutcome(messageRequestRepo.save(existing), false);
    }

    private void broadcastReaction(UUID conversationId, long messageId, UUID userId, String emoji, boolean added) {
        List<UUID> recipients = memberRepo.findReadableMemberIds(conversationId);
        broadcaster.broadcast(recipients, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MESSAGE_REACTION)
                .conversationId(conversationId)
                .messageId(messageId).userId(userId).emoji(emoji).added(added)
                .build());
    }

    private MessageByIdEntity requireReadableMessage(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        memberRepo.findMember(m.getConversationId(), userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
        return m;
    }

    /** Like {@link #requireReadableMessage} but rejects read-only (RESTRICTED)
     *  members — reacting is an interaction, not a read. */
    private MessageByIdEntity requirePostableMessage(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        ConversationMember mem = memberRepo.findMember(m.getConversationId(), userId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
        if (!mem.isActive()) {
            throw new ForbiddenException("You are restricted from interacting here.", "READ_ONLY");
        }
        return m;
    }

    private UUID otherDirectMember(UUID conversationId, UUID me) {
        return memberRepo.findAllByConversation(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .filter(id -> !id.equals(me))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Direct conversation has no peer."));
    }

    private List<MediaRef> buildMedia(List<MediaRefDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return null;
        List<MediaRef> out = new ArrayList<>(dtos.size());
        for (MediaRefDto d : dtos) {
            out.add(MediaRef.builder()
                    .kind(d.getKind())
                    .storageKey(d.getStorageKey())
                    .url(resolveUrl(d.getUrl(), d.getStorageKey()))
                    .thumbnailKey(d.getThumbnailKey())
                    .thumbnailUrl(resolveUrl(d.getThumbnailUrl(), d.getThumbnailKey()))
                    .mime(d.getMime()).bytes(d.getBytes())
                    .width(d.getWidth()).height(d.getHeight())
                    .durationMs(d.getDurationMs()).waveform(d.getWaveform())
                    .fileName(d.getFileName()).altText(d.getAltText())
                    .build());
        }
        return out;
    }

    private String resolveUrl(String url, String key) {
        if (StringUtils.hasText(url)) return url;
        if (StringUtils.hasText(key)) {
            try { return storageService.getPublicUrl(key); } catch (Exception ignored) { /* fall through */ }
        }
        return null;
    }

    private Set<UUID> resolveMentions(String body) {
        if (!StringUtils.hasText(body)) return null;
        var parsed = MentionExtractor.extract(body);
        if (parsed.getUsernames().isEmpty()) return null;
        List<User> users = userRepository.findAllByUsernameIn(parsed.getUsernames());
        if (users.isEmpty()) return null;
        return users.stream().map(User::getId).collect(Collectors.toSet());
    }

    private Map<UUID, User> loadUsers(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return userRepository.findActiveByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
    }

    private String senderLabel(UUID senderId) {
        return userRepository.findById(senderId).map(u -> "@" + u.getUsername()).orElse("Someone");
    }

    private MessageResponse mapById(long messageId, UUID viewerId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId).orElse(null);
        if (m == null) return null;
        Map<UUID, User> users = loadUsers(Set.of(m.getSenderId()));
        ReplyPreview reply = m.getReplyToId() == null ? null
                : mapper.toReplyPreview(messageByIdRepo.findById(m.getReplyToId()).orElse(null));
        return mapper.toMessage(m, users, reactionService.detailFor(messageId, viewerId), reply);
    }

    private MessageResponse echoExisting(long existingId, UUID senderId, UUID conversationId, SendMessageRequest req) {
        MessageResponse mapped = mapById(existingId, senderId);
        if (mapped != null) return mapped;
        // Winner still writing — echo from the request so the client can reconcile.
        return new MessageResponse(existingId, conversationId, senderId, null, null,
                req.getType() != null ? req.getType().name() : MessageType.TEXT.name(),
                req.getBody(), List.of(), req.getReplyToId(), null, null, null, List.of(),
                null, false, null, Instant.now());
    }

    private String previewOf(String body, String type, List<MediaRef> media) {
        if (StringUtils.hasText(body)) {
            return body.length() <= 160 ? body : body.substring(0, 159) + "…";
        }
        String kind = (media != null && !media.isEmpty() && media.get(0).getKind() != null)
                ? media.get(0).getKind() : type;
        return switch (kind == null ? "" : kind) {
            case "IMAGE" -> "📷 Photo";
            case "VIDEO" -> "🎥 Video";
            case "VOICE" -> "🎤 Voice message";
            case "FILE"  -> "📎 File";
            case "SYSTEM" -> "";
            default -> "";
        };
    }

    private static String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private record RequestOutcome(MessageRequest request, boolean justCreated) {}
}
