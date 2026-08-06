package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.response.MessageRequestResponse;
import ak.dev.irc.app.chat.entity.MessageRequest;
import ak.dev.irc.app.chat.enums.MessageRequestStatus;
import ak.dev.irc.app.chat.mapper.ChatMapper;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.MessageRequestRepository;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChatMessages;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.UserSocialService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The Message Requests inbox — list pending requests and act on them. Accepting
 * graduates the thread to a normal chat (it now passes the permission engine's
 * {@code hasAcceptedThread} check and appears in the main inbox); declining hides
 * it; blocking declines and blocks the requester through the existing social API.
 */
@Service
@RequiredArgsConstructor
public class MessageRequestService {

    private final MessageRequestRepository requestRepo;
    private final UserRepository userRepository;
    private final UserSocialService socialService;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatMapper mapper;

    @Transactional(readOnly = true)
    public Page<MessageRequestResponse> list(UUID userId, MessageRequestStatus status, Pageable pageable) {
        Page<MessageRequest> page = requestRepo.findInbox(userId,
                status == null ? MessageRequestStatus.PENDING : status, pageable);
        // One bulk requester load for the page (previously a findById per row).
        java.util.Set<UUID> requesterIds = page.getContent().stream()
                .map(MessageRequest::getRequesterId).collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, User> users = requesterIds.isEmpty() ? java.util.Map.of()
                : userRepository.findAllById(requesterIds).stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return page.map(r -> mapper.toMessageRequest(r, users.get(r.getRequesterId())));
    }

    @Transactional(readOnly = true)
    public long countPending(UUID userId) {
        return requestRepo.countByRecipientIdAndStatus(userId, MessageRequestStatus.PENDING);
    }

    @Transactional
    public void accept(UUID requestId, UUID userId) {
        MessageRequest r = requireRecipient(requestId, userId);
        r.setStatus(MessageRequestStatus.ACCEPTED);
        requestRepo.save(r);
        // Let the requester know the thread graduated (their client refetches).
        broadcaster.broadcastTo(r.getRequesterId(), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                .conversationId(r.getConversationId())
                .memberChange("REQUEST_ACCEPTED")
                .build());
    }

    @Transactional
    public void decline(UUID requestId, UUID userId) {
        MessageRequest r = requireRecipient(requestId, userId);
        r.setStatus(MessageRequestStatus.DECLINED);
        requestRepo.save(r);
    }

    @Transactional
    public void block(UUID requestId, UUID userId) {
        MessageRequest r = requireRecipient(requestId, userId);
        r.setStatus(MessageRequestStatus.BLOCKED);
        requestRepo.save(r);
        // Reuse the existing social block (runs in the recipient's security context).
        socialService.block(r.getRequesterId());
    }

    private MessageRequest requireRecipient(UUID requestId, UUID userId) {
        MessageRequest r = requestRepo.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("MessageRequest", "id", requestId));
        if (!r.getRecipientId().equals(userId)) {
            throw new ForbiddenException(
                    ChatMessages.REQUEST_NOT_ADDRESSED_MSG, ChatMessages.ACCESS_FORBIDDEN);
        }
        return r;
    }
}
