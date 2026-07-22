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

    // ── List ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MemberResponse> listMembers(UUID conversationId, UUID userId, Pageable pageable) {
        requireGroup(conversationId);
        requireReadableMember(conversationId, userId);
        Page<ConversationMember> page = memberRepo.findByConversation(conversationId, pageable);
        Set<UUID> ids = page.getContent().stream().map(m -> m.getId().getUserId()).collect(Collectors.toSet());
        Map<UUID, User> users = ids.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
        return page.map(m -> mapper.toMember(m, users.get(m.getId().getUserId())));
    }

    // ── Add ──────────────────────────────────────────────────────────────────────

    @Transactional
    public void addMembers(UUID conversationId, UUID actorId, List<UUID> userIds) {
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.ADD_MEMBERS, null, c.getGroupSettings())) {
            throw new ForbiddenException("You cannot add members to this group.", "ADMINS_ONLY");
        }

        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(userIds);
        Map<UUID, User> users = candidates.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(candidates).stream().collect(Collectors.toMap(User::getId, u -> u));
        String actorLabel = label(actorId, users);

        int added = 0;
        for (UUID id : candidates) {
            if (!users.containsKey(id)) continue;                      // no such active user
            if (relationships.isBlockedEitherWay(actorId, id)) continue; // abuse guard
            ConversationMember existing = memberRepo.findMember(conversationId, id).orElse(null);
            // Already a member (ACTIVE) — or RESTRICTED, which must NOT be silently
            // lifted by an add — so skip. Only truly-departed members re-join.
            if (existing != null && !isRejoinable(existing)) continue;
            if (existing != null) {                                     // re-activate a LEFT/REMOVED member
                existing.setStatus(MemberStatus.ACTIVE);
                existing.setRole(MemberRole.MEMBER);
                memberRepo.save(existing);
            } else {
                memberRepo.save(ConversationMember.of(c, id, MemberRole.MEMBER));
            }
            added++;
            systemMessages.write(conversationId, SystemEventType.MEMBER_ADDED, actorId,
                    actorLabel + " added " + label(id, users));
            chatNotifications.notifyAddedToGroup(id, actorId, conversationId, c.getTitle(), actorLabel);
            emitMemberChange(conversationId, id, "ADDED", MemberRole.MEMBER, true);
        }
        if (added > 0) conversationRepo.adjustMemberCount(conversationId, added);
    }

    // ── Remove (kick) ──────────────────────────────────────────────────────────────

    @Transactional
    public void removeMember(UUID conversationId, UUID actorId, UUID targetId) {
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        ConversationMember target = memberRepo.findMember(conversationId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException("The owner cannot be removed.", "NOT_OWNER");
        if (!GroupPermissions.can(actor.getRole(), GroupAction.REMOVE_MEMBER, target.getRole(), c.getGroupSettings())) {
            throw new ForbiddenException("You cannot remove this member.",
                    target.isAdminOrOwner() ? "CANNOT_ACT_ON_ADMIN" : "ADMINS_ONLY");
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
            throw new BadRequestException("role must be ADMIN or MEMBER.");
        }
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        ConversationMember target = memberRepo.findMember(conversationId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException("The owner's role cannot be changed.", "NOT_OWNER");

        boolean promote = newRole == MemberRole.ADMIN;
        GroupAction action = promote ? GroupAction.PROMOTE_ADMIN : GroupAction.DEMOTE_ADMIN;
        if (!GroupPermissions.can(actor.getRole(), action, target.getRole(), c.getGroupSettings())) {
            throw new ForbiddenException("You cannot change this member's role.", "ADMINS_ONLY");
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
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        ConversationMember target = memberRepo.findMember(conversationId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException("The owner cannot be restricted.", "NOT_OWNER");
        if (!GroupPermissions.can(actor.getRole(), GroupAction.RESTRICT_MEMBER, target.getRole(), c.getGroupSettings())) {
            throw new ForbiddenException("You cannot restrict this member.",
                    target.isAdminOrOwner() ? "CANNOT_ACT_ON_ADMIN" : "ADMINS_ONLY");
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
            throw new BadRequestException("Transfer ownership before leaving, or delete the group.");
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
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = memberRepo.findMember(conversationId, actorId)
                .orElseThrow(() -> new ForbiddenException("You are not a member.", "NOT_A_MEMBER"));
        if (!GroupPermissions.can(actor.getRole(), GroupAction.TRANSFER_OWNERSHIP, null, c.getGroupSettings())) {
            throw new ForbiddenException("Only the owner can transfer ownership.", "NOT_OWNER");
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
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.CREATE_INVITE, null, c.getGroupSettings())) {
            throw new ForbiddenException("You cannot create invite links here.", "ADMINS_ONLY");
        }
        // Rotate: revoke any existing links, mint a fresh token.
        inviteRepo.revokeAllForConversation(conversationId);

        String token = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        LocalDateTime expiresAt = req.getExpiresInHours() == null ? null
                : LocalDateTime.now().plusHours(req.getExpiresInHours());
        ConversationInvite invite = inviteRepo.save(ConversationInvite.builder()
                .conversationId(conversationId)
                .tokenHash(sha256(token))
                .createdByUser(actorId)
                .expiresAt(expiresAt)
                .maxUses(req.getMaxUses())
                .build());
        return new InviteLinkResponse(conversationId, token, invite.getExpiresAt(), invite.getMaxUses(), invite.getUseCount());
    }

    @Transactional
    public void revokeInvite(UUID conversationId, UUID actorId) {
        Conversation c = requireGroup(conversationId);
        ConversationMember actor = requireActiveMember(conversationId, actorId);
        if (!GroupPermissions.can(actor.getRole(), GroupAction.CREATE_INVITE, null, c.getGroupSettings())) {
            throw new ForbiddenException("You cannot revoke invite links here.", "ADMINS_ONLY");
        }
        inviteRepo.revokeAllForConversation(conversationId);
    }

    @Transactional
    public ConversationResponse join(UUID userId, String token) {
        ConversationInvite invite = inviteRepo.findByTokenHash(sha256(token))
                .filter(ConversationInvite::isUsable)
                .orElseThrow(() -> new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID"));
        Conversation c = conversationRepo.findById(invite.getConversationId())
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID"));

        ConversationMember existing = memberRepo.findMember(c.getId(), userId).orElse(null);
        // Already a member (ACTIVE) or RESTRICTED (read-only) → idempotent no-op;
        // a restricted member can't use an invite link to lift their restriction.
        if (existing != null && !isRejoinable(existing)) {
            return conversationService.get(c.getId(), userId);
        }
        // Atomically consume a use up-front so maxUses can't be exceeded under
        // concurrency (guarded UPDATE; 0 rows affected ⇒ exhausted/expired).
        if (inviteRepo.consumeUse(invite.getId()) == 0) {
            throw new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID");
        }
        if (existing != null) {
            existing.setStatus(MemberStatus.ACTIVE);
            existing.setRole(MemberRole.MEMBER);
            memberRepo.save(existing);
        } else {
            memberRepo.save(ConversationMember.of(c, userId, MemberRole.MEMBER));
        }
        conversationRepo.adjustMemberCount(c.getId(), 1);
        systemMessages.write(c.getId(), SystemEventType.MEMBER_ADDED, userId,
                label(userId, Map.of()) + " joined via invite link");
        emitMemberChange(c.getId(), userId, "ADDED", MemberRole.MEMBER, true);
        return conversationService.get(c.getId(), userId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private void emitMemberChange(UUID conversationId, UUID userId, String change, MemberRole role, boolean toGroup) {
        ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                .conversationId(conversationId).userId(userId)
                .memberChange(change).role(role == null ? null : role.name())
                .build();
        if (toGroup) {
            broadcaster.broadcast(memberRepo.findActiveMemberIds(conversationId), evt);
        }
        // Always deliver to the affected user so their client updates even if
        // they've just lost active membership (removed/left).
        broadcaster.broadcastTo(userId, evt);
    }

    private Conversation requireGroup(UUID conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        if (!c.isGroup()) throw new BadRequestException("This action applies only to group conversations.");
        return c;
    }

    /** Only genuinely-departed members re-join; ACTIVE and RESTRICTED are left as-is. */
    private static boolean isRejoinable(ConversationMember m) {
        return m.getStatus() == MemberStatus.LEFT || m.getStatus() == MemberStatus.REMOVED;
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException("You are not an active member of this conversation.", "NOT_A_MEMBER"));
    }

    private ConversationMember requireReadableMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
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
            throw new BadRequestException("Could not process the invite token.");
        }
    }
}
