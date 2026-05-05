package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.mapper.NotificationMapper;
import ak.dev.irc.app.user.realtime.NotificationPushedEvent;
import ak.dev.irc.app.user.realtime.NotificationUnreadCountEvent;
import ak.dev.irc.app.user.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Single entry point for "persist a notification and push it to the user".
 *
 * <p>Centralises:
 * <ul>
 *   <li><b>Aggregation</b> — high-frequency events (reactions, comments,
 *       reposts, mentions) coalesce into one inbox row per resource within a
 *       60-minute window; later events bump the count, refresh the body and
 *       lastActor, and float the row back to unread.</li>
 *   <li><b>Realtime push</b> — fires the existing
 *       {@link NotificationPushedEvent} for the new/updated row and a fresh
 *       {@link NotificationUnreadCountEvent} so badge counters stay in sync
 *       across every open tab.</li>
 * </ul>
 *
 * <p>All write paths in {@code NotificationEventConsumer} should call
 * {@link #dispatch} or {@link #dispatchAggregated} instead of saving + pushing
 * by hand.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    /** Aggregation window — events older than this start a new inbox row. */
    private static final Duration AGGREGATION_WINDOW = Duration.ofMinutes(60);

    private final NotificationRepository notifRepo;
    private final NotificationMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * One-shot notification — never aggregated. Use for events that should
     * always produce a distinct inbox row (system messages, follow, accept,
     * unblock, etc.).
     */
    @Transactional
    public Notification dispatch(Notification draft) {
        Notification saved = notifRepo.save(draft);
        publishPush(saved);
        publishUnreadCount(saved.getUser().getId());
        return saved;
    }

    /**
     * Aggregating notification — if an unread row with the same {@code groupKey}
     * exists for this user inside the aggregation window, that row is updated
     * (count++, latest actor, refreshed body, bumped to unread). Otherwise a
     * new row is inserted with {@code aggregateCount=1}.
     *
     * <p>{@code groupKey} convention: {@code TYPE:resourceId} — e.g.
     * {@code POST_REACTED:abc-123} so reacts on the same post coalesce.
     * Reaction-on-comment uses the comment id so different comments stay
     * distinct: {@code POST_COMMENT_REACTED:comment-id}.</p>
     */
    @Transactional
    public Notification dispatchAggregated(User recipient,
                                            User actor,
                                            NotificationType type,
                                            String groupKey,
                                            String title,
                                            String body,
                                            UUID resourceId,
                                            String resourceType) {
        if (groupKey == null || groupKey.isBlank()) {
            // Caller forgot to compute a key — fall back to plain insert.
            return dispatch(Notification.builder()
                    .user(recipient)
                    .actor(actor)
                    .type(type)
                    .title(title)
                    .body(body)
                    .resourceId(resourceId)
                    .resourceType(resourceType)
                    .build());
        }

        LocalDateTime since = LocalDateTime.now().minus(AGGREGATION_WINDOW);
        Optional<Notification> existing = notifRepo.findFreshestUnreadInGroup(
                recipient.getId(), groupKey, since);

        if (existing.isPresent()) {
            Notification n = existing.get();
            n.coalesce(actor, body);
            // Title is intentionally left untouched on coalesce — clients can
            // pluralise client-side from aggregateCount if desired.
            Notification saved = notifRepo.save(n);
            publishPush(saved);
            publishUnreadCount(recipient.getId());
            return saved;
        }

        Notification draft = Notification.builder()
                .user(recipient)
                .actor(actor)
                .lastActor(actor)
                .type(type)
                .title(title)
                .body(body)
                .resourceId(resourceId)
                .resourceType(resourceType)
                .groupKey(groupKey)
                .aggregateCount(1L)
                .build();
        Notification saved = notifRepo.save(draft);
        publishPush(saved);
        publishUnreadCount(recipient.getId());
        return saved;
    }

    /**
     * Nudge clients (across every open tab) to refresh the unread badge —
     * called after server-side mark-read / delete operations too.
     */
    public void publishUnreadCount(UUID userId) {
        long count = notifRepo.countByUserIdAndIsRead(userId, false);
        eventPublisher.publishEvent(new NotificationUnreadCountEvent(userId, count));
    }

    private void publishPush(Notification saved) {
        eventPublisher.publishEvent(
                new NotificationPushedEvent(saved.getUser().getId(), mapper.toResponse(saved)));
    }
}
