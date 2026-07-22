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
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
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

    // ── Create ─────────────────────────────────────────────────────────────────
    // NOTE: the controller calls createDirect / createGroup directly (not a single
    // dispatcher) so that createGroup's @Transactional engages through the Spring
    // proxy — a same-bean dispatch would self-invoke and silently drop the
    // transaction. createDirect is intentionally non-transactional (race-catch).

    /** Race-safe get-or-create for a 1:1 DM. */
    public ConversationResponse createDirect(UUID creatorId, UUID recipientId) {
        if (creatorId.equals(recipientId)) throw new BadRequestException("You cannot start a conversation with yourself.");
        userRepository.findById(recipientId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", recipientId));
        if (relationships.isBlockedEitherWay(creatorId, recipientId)) {
            throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
        }

        String key = DirectKeys.of(creatorId, recipientId);
        UUID convId = conversationRepo.findByDirectKey(key).map(Conversation::getId).orElse(null);
        if (convId == null) {
            try {
                convId = conversationFactory.createDirect(creatorId, recipientId, key);
            } catch (DataIntegrityViolationException race) {
                convId = conversationRepo.findByDirectKey(key)
                        .map(Conversation::getId)
                        .orElseThrow(() -> new BadRequestException("Could not create the conversation."));
            }
        }
        return get(convId, creatorId);
    }

    @Transactional
    public ConversationResponse createGroup(UUID creatorId, CreateConversationRequest req) {
        if (!StringUtils.hasText(req.getTitle())) {
            throw new BadRequestException("A group requires a title.");
        }
        // De-dupe, drop the creator, drop non-existent / blocked users.
        List<UUID> requested = req.getMemberIds() == null ? List.of() : req.getMemberIds();
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(requested);
        candidates.remove(creatorId);
        if (candidates.size() > MAX_GROUP_INITIAL_MEMBERS) {
            throw new BadRequestException("A group can start with at most " + MAX_GROUP_INITIAL_MEMBERS + " members.");
        }
        Map<UUID, User> users = candidates.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(candidates).stream().collect(Collectors.toMap(User::getId, u -> u));
        List<UUID> toAdd = candidates.stream()
                .filter(users::containsKey)
                .filter(id -> !relationships.isBlockedEitherWay(creatorId, id))
                .toList();

        Conversation c = conversationRepo.save(Conversation.builder()
                .type(ConversationType.GROUP)
                .title(req.getTitle().trim())
                .avatarKey(req.getAvatarKey())
                .ownerId(creatorId)
                .groupSettings(GroupSettings.defaults())
                .memberCount(1 + toAdd.size())
                .build());

        memberRepo.save(ConversationMember.of(c, creatorId, MemberRole.OWNER));
        for (UUID id : toAdd) memberRepo.save(ConversationMember.of(c, id, MemberRole.MEMBER));

        String creatorLabel = label(creatorId, users);
        systemMessages.write(c.getId(), SystemEventType.GROUP_CREATED, creatorId,
                creatorLabel + " created the group");

        // Notify + realtime the added members.
        for (UUID id : toAdd) {
            chatNotifications.notifyAddedToGroup(id, creatorId, c.getId(), c.getTitle(), creatorLabel);
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
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
        ParticipantSummary peer = null;
        if (c.isDirect()) {
            UUID peerId = memberRepo.findAllByConversation(conversationId).stream()
                    .map(m -> m.getId().getUserId()).filter(id -> !id.equals(userId)).findFirst().orElse(null);
            peer = peerId == null ? null : mapper.toParticipant(userRepository.findById(peerId).orElse(null));
        }
        return mapper.toConversation(c, me, peer);
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
        if (req.getTitle() != null || req.getAvatarKey() != null) {
            if (!GroupPermissions.can(me.getRole(), GroupAction.EDIT_INFO, null, c.getGroupSettings())) {
                throw new ForbiddenException("You cannot edit this group's info.", "ADMINS_ONLY");
            }
            if (req.getTitle() != null && !req.getTitle().equals(c.getTitle())) {
                c.setTitle(req.getTitle().trim());
                systemMessages.write(conversationId, SystemEventType.TITLE_CHANGED, userId,
                        label(userId, Map.of()) + " changed the group name to \"" + c.getTitle() + "\"");
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
                throw new ForbiddenException("You cannot change this group's settings.", "ADMINS_ONLY");
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
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
        if (lastReadMessageId <= me.getLastReadMessageId()) return; // no rewind
        me.setLastReadMessageId(lastReadMessageId);
        me.setUnreadCount(0);
        memberRepo.save(me);
        unreadBadge.invalidate(userId);

        // Blue-tick the sender(s) — but never leak receipts on a pending request
        // or a restricted thread.
        if (!relationships.suppressEphemeral(conversationId, userId)) {
            broadcaster.broadcastExcept(memberRepo.findActiveMemberIds(conversationId), userId,
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.RECEIPT_READ)
                            .conversationId(conversationId)
                            .userId(userId).lastReadMessageId(lastReadMessageId)
                            .build());
        }
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

    @Transactional
    public void delete(UUID conversationId, UUID userId) {
        Conversation c = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        ConversationMember me = requireMember(conversationId, userId);
        if (c.isGroup()) {
            if (!GroupPermissions.can(me.getRole(), GroupAction.DELETE_GROUP, null, c.getGroupSettings())) {
                throw new ForbiddenException("Only the owner can delete the group.", "NOT_OWNER");
            }
            c.setDeletedAt(LocalDateTime.now());
            conversationRepo.save(c);
            broadcaster.broadcast(memberRepo.findActiveMemberIds(conversationId),
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                            .conversationId(conversationId)
                            .memberChange("DELETED")
                            .build());
        } else {
            // Hide a DM for me only (archive my side).
            me.setArchived(true);
            memberRepo.save(me);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private Conversation requireGroup(UUID conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        if (!c.isGroup()) throw new BadRequestException("This action applies only to group conversations.");
        return c;
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException("You are not an active member of this conversation.", "NOT_A_MEMBER"));
    }

    private String label(UUID userId, Map<UUID, User> known) {
        User u = known.get(userId);
        if (u == null) u = userRepository.findById(userId).orElse(null);
        return u != null ? "@" + u.getUsername() : "Someone";
    }
}
