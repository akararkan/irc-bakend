package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.CreateInviteLinkRequest;
import ak.dev.irc.app.chat.dto.response.ConversationResponse;
import ak.dev.irc.app.chat.dto.response.InviteLinkResponse;
import ak.dev.irc.app.chat.dto.response.MemberResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationInvite;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.*;
import ak.dev.irc.app.chat.mapper.ChatMapper;
import ak.dev.irc.app.chat.permission.ChatRelationshipService;
import ak.dev.irc.app.chat.permission.GroupPermissions;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationInviteRepository;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChatMessages;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Group membership lifecycle — add, remove (kick), promote/demote, restrict,
 * leave, transfer ownership — plus Telegram-style invite links. Every authority
 * decision runs through {@link GroupPermissions#can} so the matrix is enforced in
 * exactly one place, and every change emits a SYSTEM timeline message plus a
 * {@code member.changed} realtime event.
 */
@Service
@RequiredArgsConstructor
public class GroupMemberService {

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ConversationInviteRepository inviteRepo;
    private final SystemMessageService systemMessages;
    private final ChatNotificationService chatNotifications;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatRelationshipService relationships;
    private final ChatMapper mapper;
    private final UserRepository userRepository;
    private final ConversationService conversationService;
    private final ChannelJoinRequestService joinRequestService;

    /** Web origin invite share links point at (frontend routes /join/{token}). */
    @org.springframework.beans.factory.annotation.Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    // ── List ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MemberResponse> listMembers(UUID conversationId, UUID userId, Pageable pageable) {
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember me = requireReadableMember(conversationId, userId);
        // Channels can hide their subscriber list from non-admins (count stays public).
        if (c.isChannel() && c.channelSettingsOrDefaults().isHiddenSubscribers()
                && !me.isAdminOrOwner()) {
            throw new ForbiddenException(
                    ChatMessages.SUBSCRIBERS_HIDDEN_MSG, ChatMessages.SUBSCRIBERS_HIDDEN);
        }
        Page<ConversationMember> page = memberRepo.findByConversation(conversationId, pageable);
        Set<UUID> ids = page.getContent().stream().map(m -> m.getId().getUserId()).collect(Collectors.toSet());
        Map<UUID, User> users = ids.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
        return page.map(m -> mapper.toMember(m, users.get(m.getId().getUserId())));
    }

    // ── Add ──────────────────────────────────────────────────────────────────────

    @Transactional
    public void addMembers(UUID conversationId, UUID actorId, List<UUID> userIds) {
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.ADD_MEMBERS, null, c.getGroupSettings())) {
            throw new ForbiddenException(
                    ChatMessages.ADD_MEMBERS_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }
        if (c.isChannel() && !ak.dev.irc.app.chat.permission.ChannelRights.can(
                actor, ak.dev.irc.app.chat.dto.AdminRights::isCanInviteUsers)) {
            throw new ForbiddenException(
                    ChatMessages.ADD_SUBSCRIBERS_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }

        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(userIds);
        LinkedHashSet<UUID> toLoad = new LinkedHashSet<>(candidates);
        toLoad.add(actorId);   // one round-trip covers the actor label too
        Map<UUID, User> users = userRepository.findActiveByIdIn(toLoad).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        String actorLabel = label(actorId, users);

        // Batched pre-checks: one blocked-set lookup + one membership query for the
        // whole candidate list (previously one query per candidate).
        Set<UUID> blocked = relationships.blockedEitherWayIds(actorId);
        Map<UUID, ConversationMember> existingByUser = candidates.isEmpty() ? Map.of()
                : memberRepo.findMembersIn(conversationId, candidates).stream()
                    .collect(Collectors.toMap(m -> m.getId().getUserId(), m -> m));

        List<ConversationMember> toSave = new ArrayList<>();
        List<UUID> addedIds = new ArrayList<>();
        for (UUID id : candidates) {
            if (!users.containsKey(id)) continue;                      // no such active user
            if (blocked.contains(id)) continue;                        // abuse guard
            ConversationMember existing = existingByUser.get(id);
            // Already a member (ACTIVE) — or RESTRICTED, which must NOT be silently
            // lifted by an add — so skip. Only truly-departed members re-join.
            if (existing != null && !isRejoinable(existing)) continue;
            if (existing != null) {                                     // re-activate a LEFT/REMOVED member
                existing.setStatus(MemberStatus.ACTIVE);
                existing.setRole(MemberRole.MEMBER);
                existing.setJoinSource("ADDED_BY_ADMIN");
                toSave.add(existing);
            } else {
                toSave.add(ConversationMember.of(c, id, MemberRole.MEMBER, "ADDED_BY_ADMIN"));
            }
            addedIds.add(id);
        }
        if (addedIds.isEmpty()) return;

        memberRepo.saveAll(toSave);
        conversationRepo.adjustMemberCount(conversationId, addedIds.size());

        // One member-list read shared by every per-member event below (previously
        // re-queried once per added member, by both write() and emitMemberChange).
        List<UUID> readable = memberRepo.findReadableMemberIds(conversationId);
        List<UUID> actives = memberRepo.findActiveMemberIds(conversationId);
        for (UUID id : addedIds) {
            systemMessages.write(conversationId, SystemEventType.MEMBER_ADDED, actorId,
                    actorLabel + " added " + label(id, users), readable, users);
            chatNotifications.notifyAddedToGroup(id, actorId, conversationId, c.getTitle(), actorLabel);
            emitMemberChange(conversationId, id, "ADDED", MemberRole.MEMBER, actives);
        }
    }

    // ── Remove (kick) ──────────────────────────────────────────────────────────────

    @Transactional
    public void removeMember(UUID conversationId, UUID actorId, UUID targetId) {
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        ConversationMember target = memberRepo.findMember(conversationId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException(
                ChatMessages.OWNER_NOT_REMOVABLE_MSG, ChatMessages.NOT_OWNER);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.REMOVE_MEMBER, target.getRole(), c.getGroupSettings())) {
            throw new ForbiddenException(ChatMessages.REMOVE_MEMBER_FORBIDDEN_MSG,
                    target.isAdminOrOwner() ? ChatMessages.CANNOT_ACT_ON_ADMIN : ChatMessages.ADMINS_ONLY);
        }
        target.setStatus(MemberStatus.REMOVED);
        memberRepo.save(target);
        conversationRepo.adjustMemberCount(conversationId, -1);
        systemMessages.write(conversationId, SystemEventType.MEMBER_REMOVED, actorId,
                label(actorId, Map.of()) + " removed " + label(targetId, Map.of()));
        emitMemberChange(conversationId, targetId, "REMOVED", target.getRole(), true);
    }

    // ── Promote / demote ────────────────────────────────────────────────────────────

    @Transactional
    public void changeRole(UUID conversationId, UUID actorId, UUID targetId, MemberRole newRole) {
        if (newRole != MemberRole.ADMIN && newRole != MemberRole.MEMBER) {
            throw new BadRequestException(ChatMessages.ROLE_MUST_BE_ADMIN_OR_MEMBER_MSG);
        }
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        ConversationMember target = memberRepo.findMember(conversationId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException(
                ChatMessages.OWNER_ROLE_IMMUTABLE_MSG, ChatMessages.NOT_OWNER);

        boolean promote = newRole == MemberRole.ADMIN;
        GroupAction action = promote ? GroupAction.PROMOTE_ADMIN : GroupAction.DEMOTE_ADMIN;
        if (!GroupPermissions.can(actor.getRole(), action, target.getRole(), c.getGroupSettings())) {
            throw new ForbiddenException(
                    ChatMessages.CHANGE_ROLE_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }
        if (target.getRole() == newRole) return; // no-op

        target.setRole(newRole);
        memberRepo.save(target);
        systemMessages.write(conversationId, SystemEventType.ROLE_CHANGED, actorId,
                label(actorId, Map.of()) + (promote ? " made " : " removed ")
                        + label(targetId, Map.of()) + (promote ? " an admin" : " as admin"));
        emitMemberChange(conversationId, targetId, promote ? "PROMOTED" : "DEMOTED", newRole, true);
    }

    // ── Restrict / unrestrict ───────────────────────────────────────────────────────

    @Transactional
    public void restrictMember(UUID conversationId, UUID actorId, UUID targetId, boolean restricted) {
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        ConversationMember target = memberRepo.findMember(conversationId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException(
                ChatMessages.OWNER_NOT_RESTRICTABLE_MSG, ChatMessages.NOT_OWNER);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.RESTRICT_MEMBER, target.getRole(), c.getGroupSettings())) {
            throw new ForbiddenException(ChatMessages.RESTRICT_MEMBER_FORBIDDEN_MSG,
                    target.isAdminOrOwner() ? ChatMessages.CANNOT_ACT_ON_ADMIN : ChatMessages.ADMINS_ONLY);
        }
        target.setStatus(restricted ? MemberStatus.RESTRICTED : MemberStatus.ACTIVE);
        memberRepo.save(target);
        emitMemberChange(conversationId, targetId, restricted ? "RESTRICTED" : "UNRESTRICTED", target.getRole(), true);
    }

    // ── Leave ───────────────────────────────────────────────────────────────────────

    @Transactional
    public void leave(UUID conversationId, UUID userId) {
        Conversation c = requireGroup(conversationId);
        ConversationMember me = requireActiveMember(conversationId, userId);
        if (me.isOwner() && c.getMemberCount() > 1) {
            throw new BadRequestException(ChatMessages.LEAVE_TRANSFER_FIRST_MSG);
        }
        boolean soleOwner = me.isOwner();
        MemberRole roleBefore = me.getRole();
        me.setStatus(MemberStatus.LEFT);
        memberRepo.save(me);
        conversationRepo.adjustMemberCount(conversationId, -1);
        systemMessages.write(conversationId, SystemEventType.MEMBER_LEFT, userId,
                label(userId, Map.of()) + " left");
        emitMemberChange(conversationId, userId, "LEFT", roleBefore, false);
        if (soleOwner) {
            // Sole owner leaving retires the group. Use a bulk soft-delete (not a
            // full-entity save) so it doesn't overwrite the atomic count decrement
            // above with a stale managed row.
            conversationRepo.softDelete(conversationId, LocalDateTime.now());
        }
    }

    // ── Transfer ownership ────────────────────────────────────────────────────────────

    @Transactional
    public void transferOwnership(UUID conversationId, UUID actorId, UUID newOwnerId) {
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember actor = memberRepo.findMember(conversationId, actorId)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_SHORT_MSG, ChatMessages.NOT_A_MEMBER));
        if (!GroupPermissions.can(actor.getRole(), GroupAction.TRANSFER_OWNERSHIP, null, c.getGroupSettings())) {
            throw new ForbiddenException(
                    ChatMessages.TRANSFER_OWNER_ONLY_MSG, ChatMessages.NOT_OWNER);
        }
        ConversationMember target = memberRepo.findMember(conversationId, newOwnerId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", newOwnerId));

        actor.setRole(MemberRole.ADMIN);
        target.setRole(MemberRole.OWNER);
        memberRepo.save(actor);
        memberRepo.save(target);
        c.setOwnerId(newOwnerId);
        conversationRepo.save(c);
        systemMessages.write(conversationId, SystemEventType.OWNERSHIP_TRANSFERRED, actorId,
                label(actorId, Map.of()) + " transferred ownership to " + label(newOwnerId, Map.of()));
        emitMemberChange(conversationId, newOwnerId, "PROMOTED", MemberRole.OWNER, true);
        emitMemberChange(conversationId, actorId, "DEMOTED", MemberRole.ADMIN, true);
    }

    // ── Invite links ──────────────────────────────────────────────────────────────────

    @Transactional
    public InviteLinkResponse createInvite(UUID conversationId, UUID actorId, CreateInviteLinkRequest req) {
        requireInviteManager(conversationId, actorId);
        // Rotate: revoke any existing links, mint a fresh token.
        inviteRepo.revokeAllForConversation(conversationId);
        return mintInvite(conversationId, actorId, req);
    }

    /** Create an ADDITIONAL invite link without revoking the existing ones —
     *  Telegram supports many parallel links per chat. */
    @Transactional
    public InviteLinkResponse createAdditionalInvite(UUID conversationId, UUID actorId,
                                                     CreateInviteLinkRequest req) {
        requireInviteManager(conversationId, actorId);
        return mintInvite(conversationId, actorId, req);
    }

    /** Every non-revoked link's metadata (tokens are not recoverable). */
    @Transactional(readOnly = true)
    public List<ak.dev.irc.app.chat.dto.response.InviteLinkInfoResponse> listInvites(
            UUID conversationId, UUID actorId) {
        requireInviteManager(conversationId, actorId);
        return inviteRepo.findByConversationIdAndRevokedFalse(conversationId).stream()
                .map(i -> new ak.dev.irc.app.chat.dto.response.InviteLinkInfoResponse(
                        i.getId(), i.getConversationId(), i.getCreatedByUser(), i.getCreatedAt(),
                        i.getExpiresAt(), i.getMaxUses(), i.getUseCount(), i.isRevoked(),
                        i.isRequiresApproval(),
                        i.getExpiresAt() == null && i.getMaxUses() == null))
                .toList();
    }

    @Transactional
    public void revokeInvite(UUID conversationId, UUID actorId) {
        requireInviteManager(conversationId, actorId);
        inviteRepo.revokeAllForConversation(conversationId);
    }

    /** Revoke a single link, leaving the others working. */
    @Transactional
    public void revokeOneInvite(UUID conversationId, UUID actorId, UUID inviteId) {
        requireInviteManager(conversationId, actorId);
        ConversationInvite invite = inviteRepo.findById(inviteId)
                .filter(i -> conversationId.equals(i.getConversationId()))
                .orElseThrow(() -> new ResourceNotFoundException("InviteLink", "id", inviteId));
        if (!invite.isRevoked()) {
            invite.setRevoked(true);
            inviteRepo.save(invite);
        }
    }

    @Transactional
    public ak.dev.irc.app.chat.dto.response.JoinByTokenResponse join(UUID userId, String token) {
        ConversationInvite invite = inviteRepo.findByTokenHash(sha256(token))
                .filter(ConversationInvite::isUsable)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.INVITE_INVALID_MSG, ChatMessages.INVITE_INVALID));
        Conversation c = conversationRepo.findById(invite.getConversationId())
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.INVITE_INVALID_MSG, ChatMessages.INVITE_INVALID));

        ConversationMember existing = memberRepo.findMember(c.getId(), userId).orElse(null);
        // Already a member (ACTIVE) or RESTRICTED (read-only) → idempotent no-op;
        // a restricted member can't use an invite link to lift their restriction.
        if (existing != null && !isRejoinable(existing)) {
            return new ak.dev.irc.app.chat.dto.response.JoinByTokenResponse(
                    "JOINED", conversationService.get(c.getId(), userId));
        }
        // Atomically consume a use up-front so maxUses can't be exceeded under
        // concurrency (guarded UPDATE; 0 rows affected ⇒ exhausted/expired).
        if (inviteRepo.consumeUse(invite.getId()) == 0) {
            throw new ForbiddenException(ChatMessages.INVITE_INVALID_MSG, ChatMessages.INVITE_INVALID);
        }
        // Approval-gated link → file a join request instead of joining now.
        if (invite.isRequiresApproval()) {
            joinRequestService.file(c, userId, invite.getId());
            return new ak.dev.irc.app.chat.dto.response.JoinByTokenResponse("PENDING_APPROVAL", null);
        }
        if (existing != null) {
            existing.setStatus(MemberStatus.ACTIVE);
            existing.setRole(MemberRole.MEMBER);
            existing.setJoinSource("INVITE_LINK");
            memberRepo.save(existing);
        } else {
            memberRepo.save(ConversationMember.of(c, userId, MemberRole.MEMBER, "INVITE_LINK"));
        }
        conversationRepo.adjustMemberCount(c.getId(), 1);
        systemMessages.write(c.getId(), SystemEventType.MEMBER_ADDED, userId,
                label(userId, Map.of()) + " joined via invite link");
        emitMemberChange(c.getId(), userId, "ADDED", MemberRole.MEMBER, true);
        return new ak.dev.irc.app.chat.dto.response.JoinByTokenResponse(
                "JOINED", conversationService.get(c.getId(), userId));
    }

    /** Groups: the permission matrix's CREATE_INVITE; channels additionally need
     *  the granular {@code canInviteUsers} right. */
    private void requireInviteManager(UUID conversationId, UUID actorId) {
        Conversation c = requireGroupOrChannel(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.CREATE_INVITE, null, c.getGroupSettings())) {
            throw new ForbiddenException(
                    ChatMessages.MANAGE_INVITES_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }
        if (c.isChannel() && !ak.dev.irc.app.chat.permission.ChannelRights.can(
                actor, ak.dev.irc.app.chat.dto.AdminRights::isCanInviteUsers)) {
            throw new ForbiddenException(
                    ChatMessages.MANAGE_INVITES_FORBIDDEN_MSG, ChatMessages.ADMINS_ONLY);
        }
    }

    private InviteLinkResponse mintInvite(UUID conversationId, UUID actorId, CreateInviteLinkRequest req) {
        String token = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        LocalDateTime expiresAt = req.getExpiresInHours() == null ? null
                : LocalDateTime.now().plusHours(req.getExpiresInHours());
        ConversationInvite invite = inviteRepo.save(ConversationInvite.builder()
                .conversationId(conversationId)
                .tokenHash(sha256(token))
                .createdByUser(actorId)
                .expiresAt(expiresAt)
                .maxUses(req.getMaxUses())
                .requiresApproval(req.isRequiresApproval())
                .build());
        return new InviteLinkResponse(conversationId, token, invite.getExpiresAt(), invite.getMaxUses(),
                invite.getUseCount(), ak.dev.irc.app.chat.util.ShareLinks.of(baseUrl, "/join/" + token));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private void emitMemberChange(UUID conversationId, UUID userId, String change, MemberRole role, boolean toGroup) {
        emitMemberChange(conversationId, userId, change, role,
                toGroup ? memberRepo.findActiveMemberIds(conversationId) : null);
    }

    /** Variant taking a precomputed recipient list so batch callers fetch it once. */
    private void emitMemberChange(UUID conversationId, UUID userId, String change, MemberRole role,
                                  List<UUID> groupRecipients) {
        ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                .conversationId(conversationId).userId(userId)
                .memberChange(change).role(role == null ? null : role.name())
                .build();
        if (groupRecipients != null) {
            broadcaster.broadcast(groupRecipients, evt);
        }
        // Always deliver to the affected user so their client updates even if
        // they've just lost active membership (removed/left).
        broadcaster.broadcastTo(userId, evt);
    }

    private Conversation requireGroup(UUID conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        if (!c.isGroup()) throw new BadRequestException(ChatMessages.GROUP_ONLY_ACTION_MSG);
        return c;
    }

    /** Invite links apply to groups AND channels (a private channel is shared by
     *  invite link — it has no public @handle URL). */
    private Conversation requireGroupOrChannel(UUID conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        if (!c.isGroup() && !c.isChannel()) {
            throw new BadRequestException(ChatMessages.GROUP_OR_CHANNEL_ONLY_ACTION_MSG);
        }
        return c;
    }

    /** Only genuinely-departed members re-join; ACTIVE and RESTRICTED are left as-is. */
    private static boolean isRejoinable(ConversationMember m) {
        return m.getStatus() == MemberStatus.LEFT || m.getStatus() == MemberStatus.REMOVED;
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_AN_ACTIVE_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
    }

    private ConversationMember requireReadableMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_A_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
    }

    private String label(UUID userId, Map<UUID, User> known) {
        User u = known.get(userId);
        if (u == null) u = userRepository.findById(userId).orElse(null);
        return u != null ? "@" + u.getUsername() : "Someone";
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new BadRequestException(ChatMessages.INVITE_TOKEN_FAILED_MSG);
        }
    }
}
