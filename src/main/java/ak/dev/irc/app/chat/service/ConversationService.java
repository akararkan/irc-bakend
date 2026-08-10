package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.GroupSettings;
import ak.dev.irc.app.chat.dto.request.CreateConversationRequest;
import ak.dev.irc.app.chat.dto.request.UpdateConversationRequest;
import ak.dev.irc.app.chat.dto.response.ConversationResponse;
import ak.dev.irc.app.chat.dto.response.ParticipantSummary;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.GroupAction;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.SystemEventType;
import ak.dev.irc.app.chat.mapper.ChatMapper;
import ak.dev.irc.app.chat.permission.ChatRelationshipService;
import ak.dev.irc.app.chat.permission.GroupPermissions;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.util.DirectKeys;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChatMessages;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ContentModerationService;
import ak.dev.irc.app.moderation.service.ModerationOutcome;
import ak.dev.irc.app.moderation.service.ModerationSubmission;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Conversation lifecycle + the caller's per-conversation state: create (DIRECT
 * get-or-create + GROUP), inbox listing, metadata read/update, read-marker,
 * mute/pin/archive, and delete/hide.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int MAX_GROUP_INITIAL_MEMBERS = 256;

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatConversationFactory conversationFactory;
    private final ChatRelationshipService relationships;
    private final SystemMessageService systemMessages;
    private final ChatNotificationService chatNotifications;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatMapper mapper;
    private final UserRepository userRepository;
    private final UnreadBadgeCache unreadBadge;
    private final ChatSettingsService chatSettings;
    private final ChannelService channelService;
    /** Automated text moderation of the group's title/description (docs/moderation/). */
    private final ContentModerationService contentModeration;

    // ── Create ─────────────────────────────────────────────────────────────────
    // NOTE: the controller calls createDirect / createGroup directly (not a single
    // dispatcher) so that createGroup's @Transactional engages through the Spring
    // proxy — a same-bean dispatch would self-invoke and silently drop the
    // transaction. createDirect is intentionally non-transactional (race-catch).

    /** Race-safe get-or-create for a 1:1 DM. */
    public ConversationResponse createDirect(UUID creatorId, UUID recipientId) {
        if (creatorId.equals(recipientId)) throw new BadRequestException(ChatMessages.SELF_CONVERSATION_MSG);
        userRepository.findById(recipientId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", recipientId));
        if (relationships.isBlockedEitherWay(creatorId, recipientId)) {
            throw new ForbiddenException(
                    ChatMessages.INTERACTION_NOT_ALLOWED_MSG, ChatMessages.BLOCKED);
        }

        String key = DirectKeys.of(creatorId, recipientId);
        UUID convId = conversationRepo.findByDirectKey(key).map(Conversation::getId).orElse(null);
        if (convId == null) {
            try {
                convId = conversationFactory.createDirect(creatorId, recipientId, key);
            } catch (DataIntegrityViolationException race) {
                convId = conversationRepo.findByDirectKey(key)
                        .map(Conversation::getId)
                        .orElseThrow(() -> new BadRequestException(ChatMessages.CONVERSATION_CREATE_FAILED_MSG));
            }
        }
        return get(convId, creatorId);
    }

    @Transactional
    public ConversationResponse createGroup(UUID creatorId, CreateConversationRequest req) {
        if (!StringUtils.hasText(req.getTitle())) {
            throw new BadRequestException(ChatMessages.GROUP_TITLE_REQUIRED_MSG);
        }
        // De-dupe, drop the creator, drop non-existent / blocked users.
        List<UUID> requested = req.getMemberIds() == null ? List.of() : req.getMemberIds();
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(requested);
        candidates.remove(creatorId);
        if (candidates.size() > MAX_GROUP_INITIAL_MEMBERS) {
            throw new BadRequestException(
                    ChatMessages.GROUP_MAX_INITIAL_MEMBERS_MSG.formatted(MAX_GROUP_INITIAL_MEMBERS));
        }
        Map<UUID, User> users = candidates.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(candidates).stream().collect(Collectors.toMap(User::getId, u -> u));
        // One cached blocked-set lookup instead of a per-candidate block query.
        Set<UUID> blocked = candidates.isEmpty() ? Set.of() : relationships.blockedEitherWayIds(creatorId);
        List<UUID> toAdd = candidates.stream()
                .filter(users::containsKey)
                .filter(id -> !blocked.contains(id))
                .toList();

        Conversation c = conversationRepo.save(Conversation.builder()
                .type(ConversationType.GROUP)
                .title(req.getTitle().trim())
                .description(req.getDescription() == null || req.getDescription().isBlank()
                        ? null : req.getDescription().trim())
                .avatarKey(req.getAvatarKey())
                .ownerId(creatorId)
                .groupSettings(GroupSettings.defaults())
                .memberCount(1 + toAdd.size())
                .build());

        // Group titles and descriptions are moderated as CHANNEL units — same
        // long-lived, widely-rendered piece of text, same policy. The id is
        // JPA-generated so the row exists first; a rejection throws out of this
        // @Transactional method and takes the insert with it, before any member row
        // or notification exists.
        ModerationOutcome moderation = contentModeration.submitOrThrow(
                ModerationSubmission.of(ModeratedEntityType.CHANNEL, c.getId().toString(), creatorId)
                        .field("title", c.getTitle())
                        .field("description", c.getDescription()));
        c.setModerationStatus(ChatModeration.marker(moderation));

        List<ConversationMember> rows = new ArrayList<>(toAdd.size() + 1);
        rows.add(ConversationMember.of(c, creatorId, MemberRole.OWNER));
        for (UUID id : toAdd) rows.add(ConversationMember.of(c, id, MemberRole.MEMBER));
        memberRepo.saveAll(rows);

        String creatorLabel = label(creatorId, users);
        // The GROUP_CREATED system line carries no title, so it is safe to write
        // even while the title is held.
        systemMessages.write(c.getId(), SystemEventType.GROUP_CREATED, creatorId,
                creatorLabel + " created the group");

        // Notify + realtime the added members. The invite notification quotes the
        // title into an inbox row and a push payload, which is exactly the kind of
        // one-way delivery a held title must not get; the invite itself still goes
        // out, just without the wording.
        String notifiableTitle = moderation.held() ? null : c.getTitle();
        for (UUID id : toAdd) {
            chatNotifications.notifyAddedToGroup(id, creatorId, c.getId(), notifiableTitle, creatorLabel);
            broadcaster.broadcastTo(id, ChatRealtimeEvent.builder()
                    .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                    .conversationId(c.getId())
                    .userId(id).memberChange("ADDED").role(MemberRole.MEMBER.name())
                    .build());
        }
        return get(c.getId(), creatorId);
    }

    // ── Read ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ConversationResponse get(UUID conversationId, UUID userId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        ConversationMember me = memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
        ParticipantSummary peer = null;
        Long peerLastRead = null, peerLastDelivered = null;
        if (c.isDirect()) {
            ConversationMember peerMember = memberRepo.findAllByConversation(conversationId).stream()
                    .filter(m -> !m.getId().getUserId().equals(userId)).findFirst().orElse(null);
            if (peerMember != null) {
                UUID peerId = peerMember.getId().getUserId();
                peer = mapper.toParticipant(userRepository.findById(peerId).orElse(null));
                // Expose the peer's receipt markers only when BOTH share read receipts
                // AND no restrict/pending suppression applies — otherwise a silent
                // restrict (or an unaccepted request) would leak the peer's read/
                // delivered state via this pull path even though the realtime paths
                // suppress it there.
                if (chatSettings.readReceiptsBetween(userId, peerId)
                        && !relationships.suppressEphemeral(conversationId, userId)) {
                    peerLastRead = peerMember.getLastReadMessageId();
                    peerLastDelivered = peerMember.getLastDeliveredMessageId();
                }
            }
        }
        return mapper.toConversation(c, me, peer, peerLastRead, peerLastDelivered);
    }

    @Transactional(readOnly = true)
    public Page<ConversationResponse> inbox(UUID userId, Pageable pageable) {
        return mapInbox(memberRepo.findInbox(userId, pageable), userId);
    }

    @Transactional(readOnly = true)
    public Page<ConversationResponse> archived(UUID userId, Pageable pageable) {
        return mapInbox(memberRepo.findArchived(userId, pageable), userId);
    }

    private Page<ConversationResponse> mapInbox(Page<ConversationMember> page, UUID userId) {
        List<ConversationMember> members = page.getContent();
        // Resolve DIRECT peers in one round-trip.
        List<UUID> directConvIds = members.stream()
                .map(ConversationMember::getConversation)
                .filter(Conversation::isDirect).map(Conversation::getId).toList();
        Map<UUID, UUID> peerByConv = new HashMap<>();
        if (!directConvIds.isEmpty()) {
            for (ConversationMember peer : memberRepo.findPeers(directConvIds, userId)) {
                peerByConv.putIfAbsent(peer.getId().getConversationId(), peer.getId().getUserId());
            }
        }
        Set<UUID> peerUserIds = new HashSet<>(peerByConv.values());
        Map<UUID, User> users = peerUserIds.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(peerUserIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        return page.map(m -> {
            Conversation c = m.getConversation();
            ParticipantSummary peer = null;
            if (c.isDirect()) {
                UUID peerId = peerByConv.get(c.getId());
                peer = peerId == null ? null : mapper.toParticipant(users.get(peerId));
            }
            return mapper.toConversation(c, m, peer);
        });
    }

    // ── Update ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ConversationResponse update(UUID conversationId, UUID userId, UpdateConversationRequest req) {
        Conversation c = requireGroup(conversationId);
        ConversationMember me = requireActiveMember(conversationId, userId);

        boolean touchedInfo = false;
        if (req.getTitle() != null || req.getAvatarKey() != null || req.getDescription() != null) {
            if (!GroupPermissions.can(me.getRole(), GroupAction.EDIT_INFO, null, c.getGroupSettings())) {
                throw new ForbiddenException(
                        ChatMessages.EDIT_GROUP_INFO_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
            }
            boolean titleChanged = req.getTitle() != null && !req.getTitle().equals(c.getTitle());
            boolean descriptionChanged = req.getDescription() != null
                    && !req.getDescription().equals(c.getDescription());
            if (titleChanged || descriptionChanged) {
                // §5.5 STRICT, as for channels: the TITLE_CHANGED system message
                // quotes the new name into every member's timeline the instant it is
                // applied, so an unresolved verdict refuses the change rather than
                // publishing text nobody has cleared.
                requireModeratedInfo(conversationId, userId,
                        titleChanged ? req.getTitle().trim() : c.getTitle(),
                        descriptionChanged
                                ? (req.getDescription().isBlank() ? null : req.getDescription().trim())
                                : c.getDescription());
                c.setModerationStatus(null);
            }
            if (titleChanged) {
                c.setTitle(req.getTitle().trim());
                systemMessages.write(conversationId, SystemEventType.TITLE_CHANGED, userId,
                        label(userId, Map.of()) + " changed the group name to \"" + c.getTitle() + "\"");
                touchedInfo = true;
            }
            if (descriptionChanged) {
                c.setDescription(req.getDescription().isBlank() ? null : req.getDescription().trim());
                systemMessages.write(conversationId, SystemEventType.DESCRIPTION_CHANGED, userId,
                        label(userId, Map.of()) + " changed the group description");
                touchedInfo = true;
            }
            if (req.getAvatarKey() != null && !req.getAvatarKey().equals(c.getAvatarKey())) {
                c.setAvatarKey(req.getAvatarKey());
                systemMessages.write(conversationId, SystemEventType.AVATAR_CHANGED, userId,
                        label(userId, Map.of()) + " changed the group photo");
                touchedInfo = true;
            }
        }
        if (req.getSettings() != null) {
            if (!GroupPermissions.can(me.getRole(), GroupAction.CHANGE_SETTINGS, null, c.getGroupSettings())) {
                throw new ForbiddenException(
                        ChatMessages.CHANGE_GROUP_SETTINGS_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
            }
            c.setGroupSettings(req.getSettings());
            touchedInfo = true;
        }
        conversationRepo.save(c);

        ConversationResponse resp = get(conversationId, userId);
        if (touchedInfo) {
            broadcaster.broadcast(memberRepo.findActiveMemberIds(conversationId),
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                            .conversationId(conversationId).conversation(resp)
                            .build());
        }
        return resp;
    }

    // ── Read marker ─────────────────────────────────────────────────────────────────

    @Transactional
    public void markRead(UUID conversationId, UUID userId, long lastReadMessageId) {
        ConversationMember me = memberRepo.findMember(conversationId, userId)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
        boolean advanced = lastReadMessageId > me.getLastReadMessageId();
        if (advanced) {
            me.setLastReadMessageId(lastReadMessageId);
            me.setUnreadCount(0);
        }
        // Opening the chat always clears an explicit "marked unread" flag.
        me.setMarkedUnread(false);
        memberRepo.save(me);
        unreadBadge.invalidate(userId);
        if (!advanced) return;

        // Blue-tick the sender(s) — but never leak receipts on a pending request /
        // restricted thread, or if the reader turned read receipts off (symmetric).
        if (!relationships.suppressEphemeral(conversationId, userId)
                && chatSettings.readReceiptsEnabled(userId)) {
            // Symmetric: only members who ALSO share read receipts get the blue tick,
            // so a peer who turned read receipts off never receives one over SSE.
            List<UUID> recipients = chatSettings.receiptRecipients(
                    memberRepo.findActiveMemberIds(conversationId), userId);
            if (!recipients.isEmpty()) {
                broadcaster.broadcast(recipients, ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.RECEIPT_READ)
                        .conversationId(conversationId)
                        .userId(userId).lastReadMessageId(lastReadMessageId)
                        .build());
            }
        }
    }

    /** Mark a chat as unread (WhatsApp/Telegram) — keeps it flagged unread in the
     *  inbox until it is next opened, without touching the read marker. */
    @Transactional
    public void markUnread(UUID conversationId, UUID userId) {
        requireMember(conversationId, userId);
        memberRepo.setMarkedUnread(conversationId, userId, true);
        unreadBadge.invalidate(userId);
    }

    /** Set the disappearing-messages timer (seconds; 0 = off). Group: requires
     *  {@code CHANGE_SETTINGS}; DM: either participant. Writes a system message. */
    @Transactional
    public void setDisappearing(UUID conversationId, UUID userId, int seconds) {
        if (seconds < 0) throw new BadRequestException(ChatMessages.SECONDS_NON_NEGATIVE_MSG);
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        ConversationMember me = requireActiveMember(conversationId, userId);
        if (c.isGroup()
                && !GroupPermissions.can(me.getRole(), GroupAction.CHANGE_SETTINGS, null, c.getGroupSettings())) {
            throw new ForbiddenException(
                    ChatMessages.CHANGE_GROUP_SETTINGS_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }
        c.setDisappearingSeconds(seconds);
        conversationRepo.save(c);
        systemMessages.write(conversationId, SystemEventType.DISAPPEARING_CHANGED, userId,
                label(userId, Map.of()) + (seconds == 0
                        ? " turned off disappearing messages"
                        : " set disappearing messages to " + humanDuration(seconds)));
        broadcaster.broadcast(memberRepo.findActiveMemberIds(conversationId),
                ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                        .conversationId(conversationId).conversation(get(conversationId, userId))
                        .build());
    }

    private static String humanDuration(int seconds) {
        if (seconds % 86400 == 0) return (seconds / 86400) + "d";
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    // ── Personal toggles ──────────────────────────────────────────────────────────────

    @Transactional
    public void mute(UUID conversationId, UUID userId, LocalDateTime mutedUntil) {
        ConversationMember me = requireMember(conversationId, userId);
        me.setMutedUntil(mutedUntil);
        memberRepo.save(me);
    }

    @Transactional
    public void pin(UUID conversationId, UUID userId, boolean pinned) {
        ConversationMember me = requireMember(conversationId, userId);
        me.setPinned(pinned);
        memberRepo.save(me);
    }

    @Transactional
    public void archive(UUID conversationId, UUID userId, boolean archived) {
        ConversationMember me = requireMember(conversationId, userId);
        me.setArchived(archived);
        memberRepo.save(me);
    }

    // ── Delete / hide ──────────────────────────────────────────────────────────────────

    /**
     * {@code DELETE /conversations/{id}}. The <b>group owner</b> deletes the whole
     * group for everyone (destructive soft-delete), and the <b>channel owner</b>
     * likewise deletes the whole channel. A channel <b>subscriber</b> leaves the
     * channel instead — the chat drops off their list for good rather than
     * resurfacing on the channel's next post (Telegram "delete and leave").
     * <b>Everyone else</b> — a DM participant or a non-owner group member —
     * performs a per-user <b>"delete conversation for me"</b>: the thread is
     * cleared and hidden on their side and re-surfaces (showing only newer
     * messages) the next time the peer sends. See {@link #deleteForMe}.
     */
    @Transactional
    public void delete(UUID conversationId, UUID userId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        ConversationMember me = requireMember(conversationId, userId);

        if (c.isChannel()) {
            if (me.isOwner()) channelService.deleteChannel(conversationId, userId);
            else channelService.unsubscribe(conversationId, userId);
            return;
        }

        if (c.isGroup()
                && GroupPermissions.can(me.getRole(), GroupAction.DELETE_GROUP, null, c.getGroupSettings())) {
            // Owner deletes the group for everyone.
            c.setDeletedAt(LocalDateTime.now());
            conversationRepo.save(c);
            broadcaster.broadcast(memberRepo.findActiveMemberIds(conversationId),
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                            .conversationId(conversationId)
                            .memberChange("DELETED")
                            .build());
            return;
        }
        // Everyone else: delete the conversation for me only.
        deleteForMe(c, me);
    }

    /**
     * Clear + hide the conversation for one member: mark everything up to the
     * current last message as cleared, drop it out of BOTH the inbox and the
     * archived list, unpin it, and zero the unread state. It re-appears (in the
     * inbox, showing only messages newer than the clear point) when a message with
     * a larger id arrives — the read path floors reads at {@code clearedBeforeMessageId}.
     */
    private void deleteForMe(Conversation c, ConversationMember me) {
        // For an empty conversation (no messages yet) fall back to a "now" floor in
        // Snowflake id-space. Use the LARGEST id of the current millisecond (all 22
        // node+seq bits set) so any strictly-later message re-surfaces the thread —
        // the smallest-id form could collide with a same-ms node-0/seq-0 message id
        // and leave the conversation stuck invisible.
        long highWater = c.getLastMessageId() != null
                ? c.getLastMessageId()
                : (((System.currentTimeMillis() - SnowflakeIdGenerator.CUSTOM_EPOCH) << 22) | ((1L << 22) - 1));
        me.setClearedBeforeMessageId(highWater);
        me.setArchived(false);          // out of the archived list too
        me.setPinned(false);
        if (me.getLastReadMessageId() < highWater) me.setLastReadMessageId(highWater);
        me.setUnreadCount(0);
        memberRepo.save(me);
        unreadBadge.invalidate(me.getId().getUserId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    /**
     * Scores a proposed group title/description and refuses the edit unless it
     * clears outright. Only the caller's own transaction is rolled back — the case
     * row survives in its REQUIRES_NEW transaction, so the refusal stays
     * explainable.
     */
    private void requireModeratedInfo(UUID conversationId, UUID actorId,
                                      String title, String description) {
        ModerationOutcome moderation = contentModeration.submit(
                ModerationSubmission.of(ModeratedEntityType.CHANNEL,
                                conversationId.toString(), actorId)
                        .field("title", title)
                        .field("description", description));
        if (moderation.rejected()) {
            throw new BadRequestException(ModerationMessages.CONTENT_REJECTED_MSG,
                    ModerationMessages.CONTENT_REJECTED);
        }
        if (moderation.held()) {
            throw new BadRequestException(ModerationMessages.CONTENT_UNDER_REVIEW_MSG,
                    ModerationMessages.CONTENT_UNDER_REVIEW);
        }
    }

    private Conversation requireGroup(UUID conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        if (!c.isGroup()) throw new BadRequestException(ChatMessages.GROUP_ONLY_ACTION_MSG);
        return c;
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_AN_ACTIVE_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
    }

    private String label(UUID userId, Map<UUID, User> known) {
        User u = known.get(userId);
        if (u == null) u = userRepository.findById(userId).orElse(null);
        return u != null ? "@" + u.getUsername() : "Someone";
    }
}
