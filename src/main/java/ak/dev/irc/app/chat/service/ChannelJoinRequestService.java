package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.dto.response.JoinRequestResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationJoinRequest;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.JoinRequestStatus;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.MemberStatus;
import ak.dev.irc.app.chat.enums.SystemEventType;
import ak.dev.irc.app.chat.permission.ChannelRights;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationJoinRequestRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Join requests for approval-gated channels/groups — filed by an
 * approval-required invite link or by subscribing to a public join-by-request
 * channel; decided by admins holding {@code canApproveJoinRequests}.
 */
@Service
@RequiredArgsConstructor
public class ChannelJoinRequestService {

    private final ConversationJoinRequestRepository requestRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatNotificationService chatNotifications;
    private final SystemMessageService systemMessages;
    private final UserRepository userRepository;

    // ── File ─────────────────────────────────────────────────────────────────────

    /** File (or re-file) a request. Idempotent while one is already pending. */
    @Transactional
    public JoinRequestResponse file(Conversation c, UUID userId, UUID inviteId) {
        ConversationJoinRequest r = requestRepo.findByConversationIdAndUserId(c.getId(), userId).orElse(null);
        boolean fresh = false;
        if (r == null) {
            r = requestRepo.save(ConversationJoinRequest.builder()
                    .conversationId(c.getId()).userId(userId).inviteId(inviteId)
                    .status(JoinRequestStatus.PENDING)
                    .build());
            fresh = true;
        } else if (!r.isPending()) {
            r.setStatus(JoinRequestStatus.PENDING);
            r.setInviteId(inviteId);
            r.setDecidedBy(null);
            r.setDecidedAt(null);
            r = requestRepo.save(r);
            fresh = true;
        }
        User requester = userRepository.findById(userId).orElse(null);
        JoinRequestResponse response = toResponse(r, requester);
        if (fresh) {
            List<UUID> staff = memberRepo.findStaffIds(c.getId());
            broadcaster.broadcast(staff, ChatRealtimeEvent.builder()
                    .eventType(ChatRealtimeEventType.JOIN_REQUEST_NEW)
                    .conversationId(c.getId()).userId(userId)
                    .joinRequest(response)
                    .build());
            String label = requester != null ? "@" + requester.getUsername() : "Someone";
            for (UUID adminId : staff) {
                chatNotifications.notifyJoinRequest(adminId, userId, c.getId(), label, c.getTitle());
            }
        }
        return response;
    }

    // ── List / decide ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<JoinRequestResponse> list(UUID channelId, UUID actorId, JoinRequestStatus status,
                                          Pageable pageable) {
        requireApprover(channelId, actorId);
        Page<ConversationJoinRequest> page = requestRepo
                .findByConversationIdAndStatusOrderByCreatedAtAsc(
                        channelId, status == null ? JoinRequestStatus.PENDING : status, pageable);
        Set<UUID> ids = page.getContent().stream().map(ConversationJoinRequest::getUserId)
                .collect(Collectors.toSet());
        Map<UUID, User> users = ids.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
        return page.map(r -> toResponse(r, users.get(r.getUserId())));
    }

    @Transactional
    public JoinRequestResponse approve(UUID channelId, UUID actorId, UUID targetUserId) {
        Conversation c = requireConversation(channelId);
        requireApprover(channelId, actorId);
        ConversationJoinRequest r = requirePending(channelId, targetUserId);

        ConversationMember existing = memberRepo.findMember(channelId, targetUserId).orElse(null);
        if (existing == null || existing.getStatus() == MemberStatus.LEFT
                || existing.getStatus() == MemberStatus.REMOVED) {
            if (existing != null) {
                existing.setStatus(MemberStatus.ACTIVE);
                existing.setRole(MemberRole.MEMBER);
                existing.setJoinSource("JOIN_REQUEST");
                memberRepo.save(existing);
            } else {
                memberRepo.save(ConversationMember.of(c, targetUserId, MemberRole.MEMBER, "JOIN_REQUEST"));
            }
            conversationRepo.adjustMemberCount(channelId, +1);
            systemMessages.write(channelId, SystemEventType.MEMBER_ADDED, targetUserId,
                    label(targetUserId) + " joined");
            ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
                    .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                    .conversationId(channelId).userId(targetUserId)
                    .memberChange("ADDED").role(MemberRole.MEMBER.name())
                    .build();
            broadcaster.broadcast(memberRepo.findActiveMemberIds(channelId), evt);
        }
        r.setStatus(JoinRequestStatus.APPROVED);
        r.setDecidedBy(actorId);
        r.setDecidedAt(LocalDateTime.now());
        requestRepo.save(r);
        chatNotifications.notifyJoinApproved(targetUserId, channelId, c.getTitle());
        return toResponse(r, userRepository.findById(targetUserId).orElse(null));
    }

    @Transactional
    public JoinRequestResponse reject(UUID channelId, UUID actorId, UUID targetUserId) {
        requireConversation(channelId);
        requireApprover(channelId, actorId);
        ConversationJoinRequest r = requirePending(channelId, targetUserId);
        r.setStatus(JoinRequestStatus.REJECTED);
        r.setDecidedBy(actorId);
        r.setDecidedAt(LocalDateTime.now());
        requestRepo.save(r);
        return toResponse(r, userRepository.findById(targetUserId).orElse(null));
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private ConversationJoinRequest requirePending(UUID channelId, UUID userId) {
        ConversationJoinRequest r = requestRepo.findByConversationIdAndUserId(channelId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("JoinRequest", "userId", userId));
        if (!r.isPending()) throw new BadRequestException("This join request was already decided.");
        return r;
    }

    private Conversation requireConversation(UUID id) {
        return conversationRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", id));
    }

    private void requireApprover(UUID channelId, UUID actorId) {
        ConversationMember actor = memberRepo.findMember(channelId, actorId).orElse(null);
        if (!ChannelRights.can(actor, AdminRights::isCanApproveJoinRequests)) {
            throw new ForbiddenException("You cannot manage join requests here.", "ADMINS_ONLY");
        }
    }

    private String label(UUID userId) {
        return userRepository.findById(userId).map(u -> "@" + u.getUsername()).orElse("Someone");
    }

    private JoinRequestResponse toResponse(ConversationJoinRequest r, User u) {
        return new JoinRequestResponse(
                r.getId(), r.getConversationId(), r.getUserId(),
                u != null ? u.getUsername() : null,
                u != null ? u.getFullName() : null,
                r.getStatus().name(),
                r.getCreatedAt(),
                r.getDecidedBy(), r.getDecidedAt());
    }
}
