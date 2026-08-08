package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MediaRef;
import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.dto.request.ContactDto;
import ak.dev.irc.app.chat.dto.request.LocationDto;
import ak.dev.irc.app.chat.dto.request.MediaRefDto;
import ak.dev.irc.app.chat.dto.request.PollCreateDto;
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
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.chat.util.Hashtags;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ContentModerationService;
import ak.dev.irc.app.moderation.service.ModerationOutcome;
import ak.dev.irc.app.moderation.service.ModerationSubmission;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChatMessages;
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
    private final ChannelPostFanoutService channelPostFanout;
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
    /** Automated text moderation (docs/moderation/) — every user-authored message
     *  body, poll, location label and contact name passes through it before the
     *  Cassandra write. */
    private final ContentModerationService contentModeration;

    // ── SEND ──────────────────────────────────────────────────────────────────

    @Transactional
    public MessageResponse send(UUID conversationId, UUID senderId, SendMessageRequest req) {
        rateLimiter.check("chat-send", senderId, 30, Duration.ofSeconds(10));

        Conversation convo = conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));

        ConversationMember senderMember = memberRepo.findMember(conversationId, senderId)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));

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
                    throw new ForbiddenException(
                            ChatMessages.INTERACTION_NOT_ALLOWED_MSG, ChatMessages.BLOCKED);
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
                    throw new BadRequestException(ChatMessages.POLL_PAYLOAD_TYPE_MISMATCH_MSG);
                }
                req.setType(MessageType.POLL);
                pollJson = pollService.validateAndSerialize(req.getPoll());
            } else if (req.getType() == MessageType.POLL) {
                throw new BadRequestException(ChatMessages.POLL_PAYLOAD_REQUIRED_MSG);
            }

            // Location / contact payloads — required by their types, forbidden otherwise.
            String locationJson = payloadJson(req.getType() == MessageType.LOCATION,
                    req.getLocation(), "LOCATION", "location");
            String contactJson = payloadJson(req.getType() == MessageType.CONTACT,
                    req.getContact(), "CONTACT", "contact");

            String type = req.getType() != null ? req.getType().name() : MessageType.TEXT.name();
            List<MediaRef> media = buildMedia(req.getMedia());
            Set<UUID> mentions = resolveMentions(req.getBody());

            // Automated moderation runs BEFORE the Cassandra write, so a blocked
            // message leaves no row anywhere — the same contract createPost has.
            // It is placed after the permission gates: there is no point scoring
            // text from someone who was never allowed to send it.
            ModerationOutcome moderation = contentModeration.submitOrThrow(
                    submissionFor(messageId, senderId, convo, req.getBody(),
                            req.getPoll(), req.getLocation(), req.getContact()));

            MessageResponse response = persist(convo, senderId, messageId, type,
                    req.getBody(), media, mentions, req.getReplyToId(), null, pollJson,
                    locationJson, contactJson, moderation);

            // Sending clears the user's draft for this conversation (Telegram).
            try { draftRepo.deleteByUserIdAndConversationId(senderId, conversationId); }
            catch (Exception ignored) { /* best-effort */ }

            if (moderation.held()) {
                // Everything below publishes the text — comment indexing bumps a
                // public counter and broadcasts, dispatch delivers the body to every
                // member and fires bells. ChatMessageModerationApplier runs the same
                // block if and when the verdict clears.
                log.debug("[CHAT] message {} held for moderation ({}), delivery deferred",
                        messageId, moderation.status());
                return response;
            }

            // Discussion-group comments: a reply in a channel's linked group to one
            // of that channel's posts is a comment — index it and bump the count.
            if (convo.isGroup() && req.getReplyToId() != null) {
                indexCommentIfDiscussionReply(convo, req.getReplyToId(), messageId);
            }

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
            //
            // A moderation rejection deliberately takes this same path. Holding the
            // nonce would be worse than useless: the retry would resolve it to the
            // claimed id, find no row behind it, and echoExisting would synthesise a
            // success response for a message that does not exist — the client would
            // believe a blocked message had been delivered. Re-scoring the retry
            // costs one inference call, and chat-send rate limiting already bounds
            // how often that can happen.
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
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.SOURCE_MESSAGE_INACCESSIBLE_MSG, ChatMessages.NOT_A_MEMBER));

        // Telegram "protected content": posts of a protected channel cannot be
        // forwarded out of it.
        Conversation source = conversationRepo.findById(src.getConversationId()).orElse(null);
        boolean sourceIsChannel = source != null && source.isChannel();
        if (sourceIsChannel && source.channelSettingsOrDefaults().isProtectedContent()) {
            throw new ForbiddenException(ChatMessages.PROTECTED_CONTENT_MSG,
                    ChatMessages.PROTECTED_CONTENT);
        }

        Conversation target = conversationRepo.findById(targetConversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", targetConversationId));
        ConversationMember targetMember = memberRepo.findMember(targetConversationId, senderId)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_TARGET_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));

        SendDecision decision = SendDecision.ALLOW;
        UUID directPeer = null;
        if (target.isGroup() || target.isChannel()) {
            authorizeGroupSend(targetMember, target);
        } else {
            directPeer = otherDirectMember(targetConversationId, senderId);
            decision = permissionEngine.authorizeDirectSend(senderId, directPeer);
            if (decision == SendDecision.DENY) {
                throw new ForbiddenException(
                        ChatMessages.INTERACTION_NOT_ALLOWED_MSG, ChatMessages.BLOCKED);
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

            // A forward re-publishes the text to a new audience, so it is scored as
            // its own unit against the new message id — the source's verdict was
            // reached in a different conversation and does not carry over.
            ModerationOutcome moderation = contentModeration.submitOrThrow(
                    ModerationSubmission.of(ModeratedEntityType.CHAT_MESSAGE,
                                    Long.toString(messageId), senderId)
                            .parent(targetConversationId.toString())
                            .field("body", src.getBody()));

            MessageResponse response = persist(target, senderId, messageId, src.getType(),
                    src.getBody(), src.getMedia(), null, null, src.getConversationId(),
                    src.getPoll(), // forwarded poll keeps the definition; votes start fresh
                    src.getLocation(), src.getContact(), moderation);

            // Channel forward counter (the source post's "shares"). Bumped even when
            // the copy is held: the applier CANNOT defer it. It resolves the message
            // from message_by_id, and that row records only forwardedFrom — the
            // source CONVERSATION — never the source message id, so once this frame
            // returns the counter's subject is unrecoverable. Crediting a share for a
            // forward that is later rejected overstates a cosmetic counter by one;
            // deferring it to a hook that does not exist loses it on every held
            // forward instead.
            if (sourceIsChannel) {
                channelMetrics.onForwarded(src.getConversationId(), sourceMessageId);
            }
            if (moderation.held()) {
                log.debug("[CHAT] forward {} held for moderation ({}), delivery deferred",
                        messageId, moderation.status());
                return response;
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
            throw new BadRequestException(ChatMessages.EDIT_DELETED_MESSAGE_MSG);
        }
        if (MessageType.SYSTEM.name().equals(m.getType())) {
            throw new BadRequestException(ChatMessages.EDIT_SYSTEM_MESSAGE_MSG);
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
                throw new ForbiddenException(
                        ChatMessages.EDIT_OWN_MESSAGES_ONLY_MSG, ChatMessages.ACCESS_FORBIDDEN);
            }
        }
        // §5.5 — an edit re-enters moderation. Without this, blocked text could be
        // laundered in by sending something benign and immediately PATCHing it.
        // Scored before the write, so a rejection leaves the old body in place.
        ModerationOutcome moderation = contentModeration.submitOrThrow(
                ModerationSubmission.of(ModeratedEntityType.CHAT_MESSAGE,
                                Long.toString(messageId), m.getSenderId())
                        .parent(m.getConversationId().toString())
                        .field("body", body));

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
        // Cassandra keeps exactly one revision of the body, so a borderline edit
        // cannot keep serving the previously approved wording — the roadmap's
        // "strict" option has nothing to serve. The edit is applied and the message
        // drops back into the held state (APPROVED → IN_REVIEW of §5.1): everyone
        // but its sender sees it blank until the verdict lands.
        String marker = ChatModeration.marker(moderation);
        boolean held = moderation.held();
        if (held || ChatModeration.held(m.getModerationStatus())) {
            messageRepo.setModerationStatus(m.getConversationId(), m.getBucket(), messageId, marker);
            messageByIdRepo.setModerationStatus(messageId, marker);
            m.setModerationStatus(marker);
        }

        if (ttl <= 0 && !held) chatSearch.indexAsync(m); // never index a disappearing or held body
        if (held) {
            // The edited body must not be pushed to anyone yet, but live clients are
            // still holding the previous text that no longer exists anywhere — so
            // they are told the message is now blank rather than left showing it.
            chatSearch.deleteAsync(messageId);
            broadcastEdit(m.getConversationId(), messageId, null, now);
            log.debug("[CHAT] edit of {} held for moderation ({})", messageId, moderation.status());
            return mapById(messageId, userId);
        }

        // A message that was held at send has never been fanned out, so an edit
        // that finally clears it is its FIRST delivery, not an edit broadcast.
        // Sending only MESSAGE_EDITED here would leave it readable on refresh but
        // never actually delivered: no bell, no inbox preview, no channel counter.
        if (Boolean.FALSE.equals(m.getDelivered())) {
            publishModeratedMessage(messageId);
            return mapById(messageId, userId);
        }

        broadcastEdit(m.getConversationId(), messageId, body, now);
        return mapById(messageId, userId);
    }

    private void broadcastEdit(UUID conversationId, long messageId, String body, Instant editedAt) {
        broadcaster.broadcast(memberRepo.findReadableMemberIds(conversationId),
                ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.MESSAGE_EDITED)
                        .conversationId(conversationId)
                        .messageId(messageId).body(body).editedAt(editedAt)
                        .build());
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
                throw new ForbiddenException(
                        ChatMessages.DELETE_MESSAGE_FORBIDDEN_MSG, ChatMessages.ACCESS_FORBIDDEN);
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
            // Only a post that actually counted may be decremented — a post deleted
            // while still held for moderation was never added to either counter.
            if (!ChatModeration.held(m.getModerationStatus())) {
                channelMetrics.postTypeAdjust(m.getConversationId(), m.getType(), -1);
                conversationRepo.adjustPostCount(m.getConversationId(), -1);
            }
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
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
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
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_AN_ACTIVE_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
        if (convo.isGroup()
                && !GroupPermissions.can(me.getRole(), GroupAction.PIN_MESSAGE, null, convo.getGroupSettings())) {
            throw new ForbiddenException(
                    ChatMessages.PIN_GROUP_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }
        if (convo.isChannel() && !ak.dev.irc.app.chat.permission.ChannelRights.can(
                me, ak.dev.irc.app.chat.dto.AdminRights::isCanPinMessages)) {
            throw new ForbiddenException(
                    ChatMessages.PIN_CHANNEL_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
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
            throw new ForbiddenException(
                    ChatMessages.REACTIONS_DISABLED_MSG, ChatMessages.REACTIONS_DISABLED);
        }
        List<String> allowed = settings.getAllowedReactions();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(emoji)) {
            throw new BadRequestException(ChatMessages.REACTION_NOT_ALLOWED_MSG);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────────

    /**
     * Writes the twin Cassandra rows and advances the inbox pointer. Returns the
     * mapped response.
     *
     * <p>{@code moderation} decides whether this is a publication or a hold. On a
     * hold the rows are written with the held marker and every step that pushes
     * the text outward — search indexing, the shared-media gallery, the real inbox
     * preview, the channel post counters — is skipped;
     * {@code ChatMessageModerationApplier} runs them if the verdict clears.</p>
     */
    private MessageResponse persist(Conversation convo, UUID senderId, long messageId, String type,
                                    String body, List<MediaRef> media, Set<UUID> mentions,
                                    Long replyToId, UUID forwardedFrom, String pollJson,
                                    String locationJson, String contactJson,
                                    ModerationOutcome moderation) {
        boolean held = moderation != null && moderation.held();
        String moderationMarker = ChatModeration.marker(moderation);
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
                .deleted(false).createdAt(now).moderationStatus(moderationMarker)
                // A held row has not been fanned out; the applier flips this when it is.
                .delivered(!held)
                .build();
        MessageByIdEntity byId = MessageByIdEntity.builder()
                .messageId(messageId).conversationId(convo.getId()).bucket(bucket)
                .senderId(senderId).type(type).body(emptyToNull(body))
                .media(media == null || media.isEmpty() ? null : media)
                .replyToId(replyToId).forwardedFrom(forwardedFrom)
                .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
                .tags(tags).authorSignature(signature).poll(pollJson)
                .location(locationJson).contact(contactJson)
                .deleted(false).createdAt(now).moderationStatus(moderationMarker)
                // A held row has not been fanned out; the applier flips this when it is.
                .delivered(!held)
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
        // the body (no TTL there) — store a non-revealing placeholder instead. A held
        // message uses the same placeholder for the same reason: the pointer still
        // has to advance (it drives ordering, gap sync and hasUnread) but the text
        // is not cleared to leave Cassandra yet.
        String inboxPreview = (ttl > 0 || held)
                ? disappearingPreview(type, media) : previewOf(body, type, media);
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
        if (ttl <= 0 && !held) chatSearch.indexAsync(byId);

        // Shared-media gallery index (one row per attachment + a LINK row for
        // bodies carrying a URL). Skipped for disappearing conversations: the
        // gallery table has no TTL, so its rows must not outlive the message.
        if (ttl <= 0 && !held) writeGalleryRows(convo.getId(), messageId, body, media, now);

        // Channel post accounting — live post count + per-type breakdown. Both are
        // publicly rendered counts of *live* posts, so a held post is not one yet.
        if (channelPost && !held) {
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
        // The echo goes back to the sender, who always sees their own text even
        // while it is held.
        return mapper.toMessage(row, users, List.of(), replyPreview, false, extras, senderId);
    }

    /**
     * Every user-authored text field of a send, as one submission. Poll questions,
     * option labels, venue names and contact names are all free text a sender
     * controls, so they are all scored — separately, never concatenated, so the
     * review queue can point at the exact field that tripped.
     *
     * <p>{@code SystemMessageService.write} is deliberately absent from this
     * pipeline. It bypasses {@link #persist} and its bodies are platform-composed,
     * with the only user-controlled part being an embedded group/channel title —
     * and that title is moderated where it is set ({@code ConversationService} /
     * {@code ChannelService}), so scoring it again here would double-charge the
     * same text and produce a second case nobody can act on.</p>
     */
    private ModerationSubmission submissionFor(long messageId, UUID senderId, Conversation convo,
                                               String body, PollCreateDto poll,
                                               LocationDto location, ContactDto contact) {
        ModerationSubmission submission = ModerationSubmission.of(
                        ModeratedEntityType.CHAT_MESSAGE, Long.toString(messageId), senderId)
                .parent(convo.getId().toString())
                .field("body", body);
        if (poll != null) {
            submission.field("poll_question", poll.getQuestion())
                    .fields("poll_option", poll.getOptions());
        }
        if (location != null) {
            submission.field("location_name", location.getName())
                    .field("location_address", location.getAddress());
        }
        if (contact != null) {
            submission.field("contact_first_name", contact.getFirstName())
                    .field("contact_last_name", contact.getLastName());
        }
        return submission;
    }

    /**
     * Runs the publication side effects {@link #persist} skipped while a message
     * was held for automated moderation: search indexing, the shared-media gallery,
     * the real inbox preview, channel post accounting, discussion-comment indexing,
     * delivery and bells.
     *
     * <p>Called by {@code ChatMessageModerationApplier} once a deferred verdict
     * lands, so there is exactly one implementation of the delivery fan-out rather
     * than two that can drift. The send context is re-derived from the persisted
     * rows instead of being stored; the one thing that cannot be recovered is
     * whether a DM was routed to the requests tray, so an approved held message is
     * dispatched as a normal delivery. The request row was created before the
     * Cassandra write, so the tray placement is right either way — only the bell
     * kind differs.</p>
     *
     * <p>Whether this is a first delivery or the release of an edit comes from the
     * row's own {@code delivered} flag, not from the case revision or
     * {@code editedAt}. Neither of those can tell apart "held at send, then
     * edited" from "delivered, then edited into a hold" — and the two need
     * opposite treatment. Getting it wrong either double-fans-out a message or,
     * worse, never delivers one at all.</p>
     */
    @Transactional
    public void publishModeratedMessage(long messageId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId).orElse(null);
        if (m == null || Boolean.TRUE.equals(m.getDeleted())) return;   // deleted while held
        // NULL means the row predates moderation, and those were all delivered.
        boolean edited = m.getDelivered() == null || Boolean.TRUE.equals(m.getDelivered());
        Conversation convo = conversationRepo.findById(m.getConversationId())
                .filter(c -> c.getDeletedAt() == null).orElse(null);
        if (convo == null) return;

        // Elasticsearch has no TTL, so a disappearing message is never indexed and
        // never gets gallery rows — clearing moderation does not change that.
        boolean disappearing = convo.getDisappearingSeconds() > 0;
        if (!disappearing) chatSearch.indexAsync(m);

        if (edited) {
            broadcastEdit(m.getConversationId(), messageId, m.getBody(),
                    m.getEditedAt() != null ? m.getEditedAt() : Instant.now());
            return;
        }

        Instant createdAt = m.getCreatedAt() != null ? m.getCreatedAt() : Instant.now();
        if (!disappearing) {
            writeGalleryRows(convo.getId(), messageId, m.getBody(), m.getMedia(), createdAt);
        }
        if (convo.isChannel() && !MessageType.SYSTEM.name().equals(m.getType())) {
            conversationRepo.adjustPostCount(convo.getId(), +1);
            channelMetrics.postTypeAdjust(convo.getId(), m.getType(), +1);
        }
        if (convo.isGroup() && m.getReplyToId() != null) {
            indexCommentIfDiscussionReply(convo, m.getReplyToId(), messageId);
        }

        String preview = disappearing
                ? disappearingPreview(m.getType(), m.getMedia())
                : previewOf(m.getBody(), m.getType(), m.getMedia());
        // advanceLastMessage cannot be reused here: the pointer already moved to
        // this message at hold time, so its "only move forward" guard makes it a
        // no-op and the placeholder preview would stick. This swaps the preview in
        // place, and only while this message is still the newest one.
        conversationRepo.refreshLastMessagePreview(convo.getId(), messageId, preview);

        MessageResponse response = mapById(messageId, m.getSenderId());
        if (response == null) return;
        UUID directPeer = convo.isDirect() ? directPeerOrNull(convo.getId(), m.getSenderId()) : null;
        dispatch(convo, m.getSenderId(), directPeer, SendDecision.ALLOW, null, false,
                response, preview, false);

        // Stamped only after the fan-out actually ran, so a failure mid-dispatch
        // leaves the row re-drivable rather than silently marked delivered.
        markDelivered(m);
    }

    /** Flips both denormalised rows' {@code delivered} flag. */
    private void markDelivered(MessageByIdEntity m) {
        try {
            messageByIdRepo.setDelivered(m.getMessageId(), true);
            messageRepo.setDelivered(m.getConversationId(), m.getBucket(), m.getMessageId(), true);
        } catch (Exception ex) {
            log.warn("[CHAT] could not stamp delivered on {}: {}", m.getMessageId(), ex.getMessage());
        }
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

        // @mention bells first — high-signal, so they cut through mute, presence
        // and the group-size cutoff (Telegram semantics). Only members who can
        // read the message qualify; silent sends stay fully silent; mentioned
        // members are excluded from the generic bells below so one message
        // never produces two rows for the same person.
        Set<UUID> mentionBelled = Set.of();
        if (decision == SendDecision.ALLOW && !silent
                && response != null && response.mentions() != null && !response.mentions().isEmpty()) {
            Set<UUID> members = Set.copyOf(recipients);
            Set<UUID> targets = new HashSet<>();
            for (UUID m : response.mentions()) {
                if (!m.equals(senderId) && members.contains(m)) targets.add(m);
            }
            if (!targets.isEmpty()) {
                String label = labelOf(response);
                for (UUID r : targets) {
                    chatNotifications.notifyMessageMention(r, senderId, conversationId,
                            label, preview, convo.isChannel(), convo.getTitle());
                }
                mentionBelled = targets;
            }
        }

        // Bell notifications (skip restricted; request → one MESSAGE_REQUEST).
        // The sender label comes from the already-hydrated response — no extra read.
        if (decision == SendDecision.ROUTE_TO_REQUEST) {
            if (requestJustCreated) {
                chatNotifications.notifyMessageRequest(directPeer, senderId, conversationId, labelOf(response));
            }
        } else if (decision == SendDecision.ALLOW && convo.isChannel()) {
            // Channel posts bell EVERY active, non-muted subscriber at any channel
            // size (aggregated per channel) — like POST_NEW / STREAM_STARTED, a
            // channel post is content, not a conversation turn, so it is not
            // gated on presence or the small-conversation cutoff. The fan-out
            // service pages by keyset and honors mute in the query itself.
            if (smallEnough) {
                unreadBadge.invalidateAll(recipients.stream().filter(r -> !r.equals(senderId)).toList());
            }
            if (!silent) {
                channelPostFanout.fanoutChannelPost(convo, senderId, preview, mentionBelled);
            }
        } else if (decision == SendDecision.ALLOW && smallEnough) {
            String label = labelOf(response);
            List<UUID> others = recipients.stream().filter(r -> !r.equals(senderId)).toList();
            unreadBadge.invalidateAll(others);                    // one DEL, not one per member
            if (!silent) {
                Set<UUID> online = presence.onlineAmong(others);  // one MGET, not one per member
                Set<UUID> muted  = Set.copyOf(memberRepo.findCurrentlyMutedIds(conversationId));
                for (UUID r : others) {
                    if (!online.contains(r) && !muted.contains(r) && !mentionBelled.contains(r)) {
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
            throw new ForbiddenException(ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER);
        }
        if (member.getStatus() == MemberStatus.RESTRICTED) {
            throw new ForbiddenException(ChatMessages.RESTRICTED_POSTING_MSG, ChatMessages.READ_ONLY);
        }
        if (!GroupPermissions.can(member.getRole(), GroupAction.SEND_MESSAGE, null, convo.getGroupSettings())) {
            throw new ForbiddenException(ChatMessages.ADMINS_ONLY_SEND_MSG, ChatMessages.ADMINS_ONLY);
        }
        // Channels: an admin additionally needs the granular post right.
        if (convo.isChannel() && !ak.dev.irc.app.chat.permission.ChannelRights.can(
                member, ak.dev.irc.app.chat.dto.AdminRights::isCanPostMessages)) {
            throw new ForbiddenException(ChatMessages.CHANNEL_POST_RIGHT_MSG, ChatMessages.ADMINS_ONLY);
        }
        // Platform-admin freeze (chat-channels-live.md §6): outranks every
        // channel-local right, including the owner's.
        if (convo.isChannel() && convo.getChannelSettings() != null
                && convo.getChannelSettings().isFrozenByAdmin()) {
            throw new ForbiddenException(
                    ChatMessages.CHANNEL_FROZEN_MSG,
                    ChatMessages.CHANNEL_FROZEN);
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
                    ChatMessages.TYPED_PAYLOAD_REQUIRED_MSG.formatted(typeName, field));
            return null;
        }
        if (!typeMatches) throw new BadRequestException(
                ChatMessages.TYPED_PAYLOAD_MISMATCH_MSG.formatted(field, typeName));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BadRequestException(ChatMessages.TYPED_PAYLOAD_STORE_FAILED_MSG.formatted(field));
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
            throw new ForbiddenException(
                    ChatMessages.INTERACTION_NOT_ALLOWED_MSG, ChatMessages.REQUEST_LIMIT_REACHED);
        }
        if (existing.getMessageCount() >= STRANGER_MESSAGE_CAP) {
            throw new ForbiddenException(
                    ChatMessages.STRANGER_CAP_REACHED_MSG, ChatMessages.REQUEST_LIMIT_REACHED);
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
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
        return m;
    }

    /** Like {@link #requireReadableMessage} but rejects read-only (RESTRICTED)
     *  members — reacting is an interaction, not a read. */
    private MessageByIdEntity requirePostableMessage(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        ConversationMember mem = memberRepo.findMember(m.getConversationId(), userId)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
        if (!mem.isActive()) {
            throw new ForbiddenException(
                    ChatMessages.RESTRICTED_INTERACTING_MSG, ChatMessages.READ_ONLY);
        }
        return m;
    }

    /** Like {@link #otherDirectMember} but tolerant of a peer that is gone — the
     *  deferred publication path must not fail over a half-emptied DM. */
    private UUID directPeerOrNull(UUID conversationId, UUID me) {
        return memberRepo.findAllByConversation(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .filter(id -> !id.equals(me))
                .findFirst().orElse(null);
    }

    private UUID otherDirectMember(UUID conversationId, UUID me) {
        return memberRepo.findAllByConversation(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .filter(id -> !id.equals(me))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(ChatMessages.DIRECT_NO_PEER_MSG));
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
        return mapper.toMessage(m, users, reactionService.detailFor(messageId, viewerId), reply,
                false, null, viewerId);
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
