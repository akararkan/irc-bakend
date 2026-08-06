package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.MediaRefDto;
import ak.dev.irc.app.chat.dto.request.ScheduleMessageRequest;
import ak.dev.irc.app.chat.dto.request.SendMessageRequest;
import ak.dev.irc.app.chat.dto.response.MediaRefResponse;
import ak.dev.irc.app.chat.dto.response.ScheduledMessageResponse;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.entity.ScheduledMessage;
import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.chat.enums.ScheduledMessageStatus;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.repository.ScheduledMessageRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChatMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Send-later messages (Telegram "Schedule message"). A message is queued in
 * Postgres and fired by a poller that runs it through the <b>normal</b> send path,
 * so a scheduled send gets the same permission checks, idempotency, fan-out and
 * realtime as a live one. If permission has since changed (blocked, kicked,
 * restricted) the fire fails cleanly and the row is marked {@code FAILED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledMessageService {

    private static final int BATCH = 100;

    private final ScheduledMessageRepository repo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final MessageService messageService;

    // ── API ──────────────────────────────────────────────────────────────────────

    @Transactional
    public ScheduledMessageResponse schedule(UUID conversationId, UUID userId, ScheduleMessageRequest req) {
        conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException(
                        ChatMessages.NOT_AN_ACTIVE_MEMBER_MSG, ChatMessages.NOT_A_MEMBER));
        if (req.getScheduledAt() == null || req.getScheduledAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException(ChatMessages.SCHEDULED_AT_FUTURE_MSG);
        }
        ScheduledMessage s = repo.save(ScheduledMessage.builder()
                .conversationId(conversationId)
                .senderId(userId)
                .scheduledAt(req.getScheduledAt())
                .type(req.getType() != null ? req.getType().name() : MessageType.TEXT.name())
                .body(req.getBody())
                .media(req.getMedia())
                .replyToId(req.getReplyToId())
                .silent(req.isSilent())
                .clientNonce(req.getClientNonce())
                .status(ScheduledMessageStatus.PENDING)
                .build());
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public List<ScheduledMessageResponse> listPending(UUID conversationId, UUID userId) {
        return repo.findByConversationIdAndSenderIdAndStatusOrderByScheduledAtAsc(
                        conversationId, userId, ScheduledMessageStatus.PENDING)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public void cancel(UUID scheduledId, UUID userId) {
        ScheduledMessage s = repo.findById(scheduledId)
                .orElseThrow(() -> new ResourceNotFoundException("ScheduledMessage", "id", scheduledId));
        if (!s.getSenderId().equals(userId)) {
            throw new ForbiddenException(
                    ChatMessages.SCHEDULED_NOT_YOURS_MSG, ChatMessages.ACCESS_FORBIDDEN);
        }
        if (s.getStatus() == ScheduledMessageStatus.PENDING) {
            s.setStatus(ScheduledMessageStatus.CANCELLED);
            repo.save(s);
        }
    }

    // ── Scheduler ────────────────────────────────────────────────────────────────

    /** Fire due messages. Each is sent in its own transaction so one failure can't
     *  roll back the others; the poll runs every 15s. */
    @Scheduled(fixedDelay = 15_000L)
    public void fireDue() {
        List<ScheduledMessage> due = repo.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                ScheduledMessageStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, BATCH));
        for (ScheduledMessage s : due) {
            try {
                fireOne(s.getId());
            } catch (ForbiddenException | BadRequestException | ResourceNotFoundException e) {
                // Permission/validation changed since scheduling (blocked, removed,
                // restricted, or the conversation is gone) — terminal, which is exactly
                // what FAILED is documented to mean.
                log.warn("[CHAT-SCHEDULER] scheduled message {} failed permanently: {}", s.getId(), e.getMessage());
                markFailed(s.getId());
            } catch (Exception e) {
                // Transient (rate limit, Cassandra/Redis timeout, optimistic lock) —
                // leave it PENDING so the next poll retries rather than silently
                // dropping a fully-permitted message.
                log.warn("[CHAT-SCHEDULER] scheduled message {} deferred, will retry next poll: {}",
                        s.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void fireOne(UUID id) {
        ScheduledMessage s = repo.findById(id).orElse(null);
        if (s == null || s.getStatus() != ScheduledMessageStatus.PENDING) return; // already handled
        SendMessageRequest req = new SendMessageRequest();
        req.setClientNonce(s.getClientNonce());
        req.setType(MessageType.valueOf(s.getType()));
        req.setBody(s.getBody());
        req.setReplyToId(s.getReplyToId());
        req.setMedia(s.getMedia());
        req.setSilent(s.isSilent());
        var sent = messageService.send(s.getConversationId(), s.getSenderId(), req);
        s.setStatus(ScheduledMessageStatus.SENT);
        s.setSentMessageId(sent.messageId());
        repo.save(s);
    }

    @Transactional
    public void markFailed(UUID id) {
        repo.findById(id).ifPresent(s -> {
            if (s.getStatus() == ScheduledMessageStatus.PENDING) {
                s.setStatus(ScheduledMessageStatus.FAILED);
                repo.save(s);
            }
        });
    }

    private ScheduledMessageResponse toResponse(ScheduledMessage s) {
        List<MediaRefResponse> media = s.getMedia() == null ? List.of()
                : s.getMedia().stream().map(this::toMediaResponse).toList();
        return new ScheduledMessageResponse(
                s.getId(), s.getConversationId(), s.getType(), s.getBody(), media,
                s.getReplyToId(), s.getScheduledAt(), s.getStatus().name(),
                s.getSentMessageId(),
                s.getCreatedAt() != null ? s.getCreatedAt() : LocalDateTime.now(ZoneOffset.UTC));
    }

    private MediaRefResponse toMediaResponse(MediaRefDto d) {
        return new MediaRefResponse(d.getKind(), d.getStorageKey(), d.getUrl(), d.getThumbnailKey(),
                d.getThumbnailUrl(), d.getMime(), d.getBytes(), d.getWidth(), d.getHeight(),
                d.getDurationMs(), d.getWaveform(), d.getFileName(), d.getAltText());
    }
}
