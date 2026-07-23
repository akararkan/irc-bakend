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
import ak.dev.irc.app.chat.util.Hashtags;
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
    private final ak.dev.irc.app.chat.repository.HiddenMessageRepository hiddenRepo;
    private final ak.dev.irc.app.chat.repository.MessageStarRepository starRepo;
    private final ChatSettingsService chatSettings;
    private final org.springframework.data.cassandra.core.CassandraOperations cassandraOps;
    private final ChannelPostMetricsService channelMetrics;
    private final PollService pollService;
    private final ak.dev.irc.app.chat.cassandra.repository.MediaByConversationRepository mediaGalleryRepo;
    private final ak.dev.irc.app.chat.cassandra.repository.ChatCommentByPostRepository commentRepo;
    private final ak.dev.irc.app.chat.repository.ConversationDraftRepository draftRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
            if (convo.isGroup() || convo.isChannel()) {
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

            // Poll payload — POLL messages must carry one, others must not.
            String pollJson = null;
            if (req.getPoll() != null) {
                if (req.getType() != null && req.getType() != MessageType.POLL) {
                    throw new BadRequestException("A poll payload requires type POLL.");
                }
                req.setType(MessageType.POLL);
                pollJson = pollService.validateAndSerialize(req.getPoll());
            } else if (req.getType() == MessageType.POLL) {
                throw new BadRequestException("A POLL message requires a poll payload.");
            }

            // Location / contact payloads — required by their types, forbidden otherwise.
            String locationJson = payloadJson(req.getType() == MessageType.LOCATION,
                    req.getLocation(), "LOCATION", "location");
            String contactJson = payloadJson(req.getType() == MessageType.CONTACT,
                    req.getContact(), "CONTACT", "contact");

            String type = req.getType() != null ? req.getType().name() : MessageType.TEXT.name();
            List<MediaRef> media = buildMedia(req.getMedia());
            Set<UUID> mentions = resolveMentions(req.getBody());

            MessageResponse response = persist(convo, senderId, messageId, type,
                    req.getBody(), media, mentions, req.getReplyToId(), null, pollJson,
                    locationJson, contactJson);

            // Discussion-group comments: a reply in a channel's linked group to one
            // of that channel's posts is a comment — index it and bump the count.
            if (convo.isGroup() && req.getReplyToId() != null) {
                indexCommentIfDiscussionReply(convo, req.getReplyToId(), messageId);
            }

            // Sending clears the user's draft for this conversation (Telegram).
            try { draftRepo.deleteByUserIdAndConversationId(senderId, conversationId); }
            catch (Exception ignored) { /* best-effort */ }

            // Redact the notification/inbox preview for disappearing conversations so
            // the body doesn't linger in a push notification either.
            String preview = convo.getDisappearingSeconds() > 0
                    ? disappearingPreview(type, media)
                    : previewOf(req.getBody(), type, media);
            dispatch(convo, senderId, directPeer, decision, request, requestJustCreated,
                    response, preview, req.isSilent());

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

        // Telegram "protected content": posts of a protected channel cannot be
        // forwarded out of it.
        Conversation source = conversationRepo.findById(src.getConversationId()).orElse(null);
        boolean sourceIsChannel = source != null && source.isChannel();
        if (sourceIsChannel && source.channelSettingsOrDefaults().isProtectedContent()) {
            throw new ForbiddenException("This channel's content is protected and cannot be forwarded.",
                    "PROTECTED_CONTENT");
        }

        Conversation target = conversationRepo.findById(targetConversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", targetConversationId));
        ConversationMember targetMember = memberRepo.findMember(targetConversationId, senderId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of the target.", "NOT_A_MEMBER"));

        SendDecision decision = SendDecision.ALLOW;
        UUID directPeer = null;
        if (target.isGroup() || target.isChannel()) {
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
                    src.getBody(), src.getMedia(), null, null, src.getConversationId(),
                    src.getPoll(), // forwarded poll keeps the definition; votes start fresh
                    src.getLocation(), src.getContact());

            // Channel forward counter (the source post's "shares").
            if (sourceIsChannel) {
                channelMetrics.onForwarded(src.getConversationId(), sourceMessageId);
            }

            String preview = target.getDisappearingSeconds() > 0
                    ? disappearingPreview(src.getType(), src.getMedia())
                    : previewOf(src.getBody(), src.getType(), src.getMedia());
            dispatch(target, senderId, directPeer, decision, request, requestJustCreated,
                    response, preview, false);
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
            // Channel posts belong to the channel: any admin with the edit right
            // may edit them (Telegram's canEditMessages).
            Conversation convo = conversationRepo.findById(m.getConversationId()).orElse(null);
            ConversationMember me = memberRepo.findMember(m.getConversationId(), userId).orElse(null);
            boolean channelAdminEdit = convo != null && convo.isChannel()
                    && ak.dev.irc.app.chat.permission.ChannelRights.can(
                            me, ak.dev.irc.app.chat.dto.AdminRights::isCanEditMessages);
            if (!channelAdminEdit) {
                throw new ForbiddenException("You can only edit your own messages.", "ACCESS_FORBIDDEN");
            }
        }
        Instant now = Instant.now();
        // Preserve the disappearing guarantee: an edit must NOT resurrect a TTL'd
        // message. Re-apply the remaining TTL so the edited body expires with the rest
        // of the row (a plain UPDATE writes body with no TTL = forever).
        int ttl = remainingDisappearingTtl(m.getConversationId(), m.getCreatedAt(), now);
        if (ttl > 0) {
            messageRepo.editBodyWithTtl(m.getConversationId(), m.getBucket(), messageId, body, now, ttl);
            messageByIdRepo.editBodyWithTtl(messageId, body, now, ttl);
        } else {
            messageRepo.editBody(m.getConversationId(), m.getBucket(), messageId, body, now);
            messageByIdRepo.editBody(messageId, body, now);
        }
        m.setBody(body);
        m.setEditedAt(now);
        // Hashtags follow the body. Skipped for disappearing rows (a no-TTL tags
        // cell would outlive the message).
        if (ttl <= 0) {
            Set<String> tags = Hashtags.extract(body);
            messageRepo.updateTags(m.getConversationId(), m.getBucket(), messageId, tags);
            messageByIdRepo.updateTags(messageId, tags);
            m.setTags(tags);
        }
        if (ttl <= 0) chatSearch.indexAsync(m); // re-index the edited body (never a disappearing one)

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

        Conversation convo = conversationRepo.findById(m.getConversationId()).orElse(null);
        boolean own = userId.equals(m.getSenderId());
        if (!own) {
            // Group admins/owners may delete anyone's message; channel admins need
            // the granular delete right.
            ConversationMember me = memberRepo.findMember(m.getConversationId(), userId).orElse(null);
            boolean adminDelete;
            if (convo != null && convo.isChannel()) {
                adminDelete = ak.dev.irc.app.chat.permission.ChannelRights.can(
                        me, ak.dev.irc.app.chat.dto.AdminRights::isCanDeleteMessages);
            } else {
                adminDelete = convo != null && convo.isGroup() && me != null && me.isActive()
                        && GroupPermissions.can(me.getRole(), GroupAction.DELETE_ANY_MESSAGE, null, convo.getGroupSettings());
            }
            if (!adminDelete) {
                throw new ForbiddenException("You cannot delete this message.", "ACCESS_FORBIDDEN");
            }
        }
        messageRepo.tombstone(m.getConversationId(), m.getBucket(), messageId);
        messageByIdRepo.tombstone(messageId);
        reactionService.clear(messageId);
        chatSearch.deleteAsync(messageId);
        pinRepo.deletePin(m.getConversationId(), messageId); // a deleted message can't stay pinned
        starRepo.deleteByMessageId(messageId);   // documented: a deleted message drops its stars
        hiddenRepo.deleteByMessageId(messageId); // and its per-user "delete for me" rows
        if (StringUtils.hasText(m.getPoll())) pollService.clear(messageId); // poll votes + counts
        dropGalleryRows(m);                      // shared-media gallery index rows
        dropCommentLinks(convo, m);              // discussion-comment index rows
        if (convo != null && convo.isChannel() && !MessageType.SYSTEM.name().equals(m.getType())) {
            channelMetrics.clear(m.getConversationId(), messageId);          // views/forwards
            channelMetrics.postTypeAdjust(m.getConversationId(), m.getType(), -1);
            conversationRepo.adjustPostCount(m.getConversationId(), -1);
        }

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
        enforceReactionPolicy(m.getConversationId(), emoji);
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

    @Transactional
    public void markDelivered(long messageId, UUID userId) {
        MessageByIdEntity m = requireReadableMessage(messageId, userId);
        // Persist the delivered high-water so the DM double-tick survives (idempotent, forward-only).
        memberRepo.advanceDeliveredMarker(m.getConversationId(), userId, messageId);
        // Don't leak receipts on a pending request / restricted thread, or if the
        // user turned read receipts off (they don't give the delivered signal either).
        if (relationships.suppressEphemeral(m.getConversationId(), userId)) return;
        if (!chatSettings.readReceiptsEnabled(userId)) return;
        // Symmetric: only members who ALSO share read receipts receive the double-tick,
        // so a peer who turned receipts off never gets one (for a DM that's the peer
        // iff both share; for a group, the opted-in subset).
        List<UUID> recipients = chatSettings.receiptRecipients(
                memberRepo.findActiveMemberIds(m.getConversationId()), userId);
        if (recipients.isEmpty()) return;
        broadcaster.broadcast(recipients, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.RECEIPT_DELIVERED)
                .conversationId(m.getConversationId())
                .messageId(messageId).userId(userId)
                .build());
    }

    // ── SEEN BY (group read receipts) ───────────────────────────────────────────────

    /** Members who have seen a message (read marker ≥ it), excluding the sender and
     *  anyone who has read receipts turned off. Empty if the caller has receipts off
     *  (symmetric — you can't see what you won't share). */
    @Transactional(readOnly = true)
    public List<ak.dev.irc.app.chat.dto.response.ParticipantSummary> seenBy(long messageId, UUID userId) {
        MessageByIdEntity m = requireReadableMessage(messageId, userId);
        // Same baseline suppression as the realtime receipt paths: a pending request or
        // a restricted DM must not leak the peer's read state via this pull path.
        if (relationships.suppressEphemeral(m.getConversationId(), userId)) return List.of();
        if (!chatSettings.readReceiptsEnabled(userId)) return List.of();
        List<UUID> seers = memberRepo.findSeenBy(m.getConversationId(), messageId, m.getSenderId());
        if (seers.isEmpty()) return List.of();
        Set<UUID> sharing = chatSettings.receiptSharersAmong(seers);
        List<UUID> visible = seers.stream().filter(sharing::contains).toList();
        if (visible.isEmpty()) return List.of();
        Map<UUID, User> users = loadUsers(new HashSet<>(visible));
        return visible.stream().map(id -> mapper.toParticipant(users.get(id)))
                .filter(java.util.Objects::nonNull).toList();
    }

    // ── DELETE FOR ME (per-message hide) ─────────────────────────────────────────────

    /** Hide a single message for the caller only ("delete for me") — the message
     *  stays in the timeline for everyone else. Any readable member may hide any
     *  message on their own side. */
    @Transactional
    public void deleteForMe(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        memberRepo.findMember(m.getConversationId(), userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
        if (hiddenRepo.existsByUserIdAndMessageId(userId, messageId)) return; // idempotent
        hiddenRepo.save(ak.dev.irc.app.chat.entity.HiddenMessage.builder()
                .userId(userId).messageId(messageId).conversationId(m.getConversationId())
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
        if (convo.isChannel() && !ak.dev.irc.app.chat.permission.ChannelRights.can(
                me, ak.dev.irc.app.chat.dto.AdminRights::isCanPinMessages)) {
            throw new ForbiddenException("You cannot pin posts in this channel.", "ADMINS_ONLY");
        }
        return convo;
    }

    /** Channel reaction policy — reactions can be disabled or limited to a set of
     *  emoji by the channel's settings. */
    private void enforceReactionPolicy(UUID conversationId, String emoji) {
        Conversation convo = conversationRepo.findById(conversationId).orElse(null);
        if (convo == null || !convo.isChannel()) return;
        var settings = convo.channelSettingsOrDefaults();
        if (!settings.isReactionsEnabled()) {
            throw new ForbiddenException("Reactions are disabled in this channel.", "REACTIONS_DISABLED");
        }
        List<String> allowed = settings.getAllowedReactions();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(emoji)) {
            throw new BadRequestException("This reaction is not allowed in this channel.");
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────────

    /** Writes the twin Cassandra rows and advances the inbox pointer. Returns the mapped response. */
    private MessageResponse persist(Conversation convo, UUID senderId, long messageId, String type,
                                    String body, List<MediaRef> media, Set<UUID> mentions,
                                    Long replyToId, UUID forwardedFrom, String pollJson,
                                    String locationJson, String contactJson) {
        int bucket = ChatBuckets.bucketOf(messageId);
        Instant now = Instant.now();

        // Sender loaded up-front: the response needs it anyway, and a signed
        // channel post stamps the posting admin's label at write time.
        Map<UUID, User> users = loadUsers(Set.of(senderId));
        boolean channelPost = convo.isChannel() && !MessageType.SYSTEM.name().equals(type);
        String signature = null;
        if (channelPost && convo.channelSettingsOrDefaults().isSignMessages()) {
            User sender = users.get(senderId);
            signature = sender != null ? "@" + sender.getUsername() : null;
        }
        Set<String> tags = Hashtags.extract(body);

        MessageByConversationEntity row = MessageByConversationEntity.builder()
                .conversationId(convo.getId()).bucket(bucket).messageId(messageId)
                .senderId(senderId).type(type).body(emptyToNull(body))
                .media(media == null || media.isEmpty() ? null : media)
                .replyToId(replyToId).forwardedFrom(forwardedFrom)
                .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
                .tags(tags).authorSignature(signature).poll(pollJson)
                .location(locationJson).contact(contactJson)
                .deleted(false).createdAt(now)
                .build();
        MessageByIdEntity byId = MessageByIdEntity.builder()
                .messageId(messageId).conversationId(convo.getId()).bucket(bucket)
                .senderId(senderId).type(type).body(emptyToNull(body))
                .media(media == null || media.isEmpty() ? null : media)
                .replyToId(replyToId).forwardedFrom(forwardedFrom)
                .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
                .tags(tags).authorSignature(signature).poll(pollJson)
                .location(locationJson).contact(contactJson)
                .deleted(false).createdAt(now)
                .build();
        // Disappearing messages: write with a Cassandra TTL so the rows auto-delete.
        int ttl = convo.getDisappearingSeconds();
        if (ttl > 0) {
            var opts = org.springframework.data.cassandra.core.InsertOptions.builder()
                    .ttl(java.time.Duration.ofSeconds(ttl)).build();
            cassandraOps.insert(row, opts);
            cassandraOps.insert(byId, opts);
        } else {
            messageRepo.save(row);
            messageByIdRepo.save(byId);
        }

        // For a disappearing conversation the Postgres inbox preview must not persist
        // the body (no TTL there) — store a non-revealing placeholder instead.
        String inboxPreview = ttl > 0 ? disappearingPreview(type, media) : previewOf(body, type, media);
        conversationRepo.advanceLastMessage(convo.getId(), messageId,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), inboxPreview);

        // The sender has, by definition, "read" their own message — advance their
        // own marker so they're never shown as unread on their own latest message
        // (and hasUnread stays false for them). Covers send and forward.
        memberRepo.advanceOwnMarker(convo.getId(), senderId, messageId);
        unreadBadge.invalidate(senderId);

        // Index for full-text search (async, best-effort — never blocks the send).
        // Never index a disappearing message: Elasticsearch has no TTL, so its body
        // would outlive the Cassandra row it "disappears" with.
        if (ttl <= 0) chatSearch.indexAsync(byId);

        // Shared-media gallery index (one row per attachment + a LINK row for
        // bodies carrying a URL). Skipped for disappearing conversations: the
        // gallery table has no TTL, so its rows must not outlive the message.
        if (ttl <= 0) writeGalleryRows(convo.getId(), messageId, body, media, now);

        // Channel post accounting — live post count + per-type breakdown.
        if (channelPost) {
            conversationRepo.adjustPostCount(convo.getId(), +1);
            channelMetrics.postTypeAdjust(convo.getId(), type, +1);
        }

        ReplyPreview replyPreview = replyToId == null ? null
                : mapper.toReplyPreview(messageByIdRepo.findById(replyToId).orElse(null));
        // The send echo carries the freshly-created poll (zero votes) and, for
        // channel posts, zeroed counters — so clients render without a re-fetch.
        ChatMapper.MessageExtras extras = new ChatMapper.MessageExtras(
                channelPost ? 0L : null, channelPost ? 0L : null, channelPost ? 0L : null,
                pollJson != null ? pollService.renderFor(byId, senderId) : null);
        return mapper.toMessage(row, users, List.of(), replyPreview, false, extras);
    }

    /** Write the (conversation, kind)-partitioned gallery rows for a new message. */
    private void writeGalleryRows(UUID conversationId, long messageId, String body,
                                  List<MediaRef> media, Instant now) {
        try {
            List<ak.dev.irc.app.chat.cassandra.entity.MediaByConversationEntity> rows = new ArrayList<>();
            if (media != null) {
                for (int i = 0; i < media.size(); i++) {
                    String kind = media.get(i).getKind();
                    if (!StringUtils.hasText(kind)) continue;
                    rows.add(ak.dev.irc.app.chat.cassandra.entity.MediaByConversationEntity.builder()
                            .conversationId(conversationId).kind(kind)
                            .messageId(messageId).mediaIndex(i).createdAt(now)
                            .build());
                }
            }
            if (Hashtags.hasLink(body)) {
                rows.add(ak.dev.irc.app.chat.cassandra.entity.MediaByConversationEntity.builder()
                        .conversationId(conversationId).kind("LINK")
                        .messageId(messageId).mediaIndex(0).createdAt(now)
                        .build());
            }
            if (!rows.isEmpty()) mediaGalleryRepo.saveAll(rows);
        } catch (Exception e) {
            log.debug("[CHAT] gallery index write failed for {}: {}", messageId, e.getMessage());
        }
    }

    /** Comment-index cleanup on delete: a deleted comment decrements its post's
     *  count; a deleted channel post drops its whole comment index (the comment
     *  messages themselves stay in the discussion group's log). */
    private void dropCommentLinks(Conversation convo, MessageByIdEntity m) {
        try {
            if (convo == null) return;
            if (convo.isChannel()) {
                commentRepo.deleteAllForPost(m.getMessageId());
                return;
            }
            if (convo.isGroup() && m.getReplyToId() != null) {
                Conversation channel = conversationRepo.findByLinkedGroupId(convo.getId()).orElse(null);
                if (channel == null) return;
                MessageByIdEntity post = messageByIdRepo.findById(m.getReplyToId()).orElse(null);
                if (post == null || !channel.getId().equals(post.getConversationId())) return;
                commentRepo.deleteOne(m.getReplyToId(), m.getMessageId());
                channelMetrics.onCommentDelta(m.getReplyToId(), -1);
                broadcaster.broadcast(memberRepo.findActiveMemberIds(channel.getId()),
                        ChatRealtimeEvent.builder()
                                .eventType(ChatRealtimeEventType.MESSAGE_COMMENT)
                                .conversationId(channel.getId())
                                .messageId(m.getReplyToId()).added(false)
                                .build());
            }
        } catch (Exception e) {
            log.debug("[CHAT] comment index cleanup failed for {}: {}", m.getMessageId(), e.getMessage());
        }
    }

    /** Remove a deleted message's gallery rows (kinds derived from its content). */
    private void dropGalleryRows(MessageByIdEntity m) {
        try {
            Set<String> kinds = new HashSet<>();
            if (m.getMedia() != null) {
                for (MediaRef ref : m.getMedia()) {
                    if (StringUtils.hasText(ref.getKind())) kinds.add(ref.getKind());
                }
            }
            if (Hashtags.hasLink(m.getBody())) kinds.add("LINK");
            for (String kind : kinds) {
                mediaGalleryRepo.deleteForMessage(m.getConversationId(), kind, m.getMessageId());
            }
        } catch (Exception e) {
            log.debug("[CHAT] gallery index cleanup failed for {}: {}", m.getMessageId(), e.getMessage());
        }
    }

    /** Unread fan-out, realtime broadcast, and offline notifications. A silent
     *  post ({@code silent=true}) delivers and counts normally but never pushes
     *  a bell notification. */
    private void dispatch(Conversation convo, UUID senderId, UUID directPeer, SendDecision decision,
                          MessageRequest request, boolean requestJustCreated,
                          MessageResponse response, String preview, boolean silent) {
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
        // The sender label comes from the already-hydrated response — no extra read.
        if (decision == SendDecision.ROUTE_TO_REQUEST) {
            if (requestJustCreated) {
                chatNotifications.notifyMessageRequest(directPeer, senderId, conversationId, labelOf(response));
            }
        } else if (decision == SendDecision.ALLOW && smallEnough) {
            String label = labelOf(response);
            List<UUID> others = recipients.stream().filter(r -> !r.equals(senderId)).toList();
            unreadBadge.invalidateAll(others);                    // one DEL, not one per member
            if (!silent) {
                Set<UUID> online = presence.onlineAmong(others);  // one MGET, not one per member
                for (UUID r : others) {
                    if (!online.contains(r)) {
                        chatNotifications.notifyNewMessage(r, senderId, conversationId, label, preview);
                    }
                }
            }
        }
    }

    private static String labelOf(MessageResponse response) {
        return response != null && response.senderUsername() != null
                ? "@" + response.senderUsername() : "Someone";
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
        // Channels: an admin additionally needs the granular post right.
        if (convo.isChannel() && !ak.dev.irc.app.chat.permission.ChannelRights.can(
                member, ak.dev.irc.app.chat.dto.AdminRights::isCanPostMessages)) {
            throw new ForbiddenException("You do not have the right to post in this channel.", "ADMINS_ONLY");
        }
        // Slow mode: non-admin group members get one message per window.
        if (convo.isGroup() && convo.getGroupSettings() != null && !member.isAdminOrOwner()) {
            int slow = convo.getGroupSettings().getSlowModeSeconds();
            if (slow > 0) {
                rateLimiter.check("chat-slow-" + convo.getId(), member.getId().getUserId(),
                        1, Duration.ofSeconds(slow));
            }
        }
    }

    /** Serialize a typed payload (location/contact) enforcing type ↔ payload pairing. */
    private String payloadJson(boolean typeMatches, Object payload, String typeName, String field) {
        if (payload == null) {
            if (typeMatches) throw new BadRequestException(
                    "A " + typeName + " message requires a " + field + " payload.");
            return null;
        }
        if (!typeMatches) throw new BadRequestException(
                "A " + field + " payload requires type " + typeName + ".");
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BadRequestException("Could not store the " + field + " payload.");
        }
    }

    /** If {@code group} is a channel's discussion group and {@code replyToId} is a
     *  post of that channel, index the new message as a comment on the post. */
    private void indexCommentIfDiscussionReply(Conversation group, long replyToId, long commentId) {
        try {
            Conversation channel = conversationRepo.findByLinkedGroupId(group.getId()).orElse(null);
            if (channel == null) return;
            MessageByIdEntity post = messageByIdRepo.findById(replyToId).orElse(null);
            if (post == null || !channel.getId().equals(post.getConversationId())
                    || Boolean.TRUE.equals(post.getDeleted())) {
                return;
            }
            commentRepo.save(ak.dev.irc.app.chat.cassandra.entity.ChatCommentByPostEntity.builder()
                    .postMessageId(replyToId).commentMessageId(commentId)
                    .createdAt(Instant.now())
                    .build());
            channelMetrics.onCommentDelta(replyToId, +1);
            broadcaster.broadcast(memberRepo.findActiveMemberIds(channel.getId()),
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.MESSAGE_COMMENT)
                            .conversationId(channel.getId())
                            .messageId(replyToId).added(true)
                            .build());
        } catch (Exception e) {
            log.debug("[CHAT] comment index failed for {}: {}", commentId, e.getMessage());
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
                null, false, null, Instant.now(), false, null, null, null, null, null, null, null, null);
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
            case "AUDIO" -> "🎵 Audio";
            case "GIF" -> "GIF";
            case "STICKER" -> "Sticker";
            case "POLL" -> "📊 Poll";
            case "FILE"  -> "📎 File";
            case "SYSTEM" -> "";
            default -> "";
        };
    }

    /** Non-revealing inbox/notification preview for a disappearing message — the media
     *  kind if any, else a generic label; never the body (which must not persist past
     *  the TTL in Postgres or a push notification). */
    private String disappearingPreview(String type, List<MediaRef> media) {
        String p = previewOf(null, type, media);
        return p.isEmpty() ? "🕓 Disappearing message" : p;
    }

    /** Remaining seconds of a disappearing message's TTL (0 if the conversation is not
     *  disappearing) — keeps an edited body expiring with the original row. */
    private int remainingDisappearingTtl(UUID conversationId, Instant createdAt, Instant now) {
        Conversation convo = conversationRepo.findById(conversationId).orElse(null);
        if (convo == null || convo.getDisappearingSeconds() <= 0) return 0;
        long elapsed = createdAt == null ? 0 : Math.max(0, Duration.between(createdAt, now).getSeconds());
        return (int) Math.max(1, convo.getDisappearingSeconds() - elapsed);
    }

    private static String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private record RequestOutcome(MessageRequest request, boolean justCreated) {}
}
