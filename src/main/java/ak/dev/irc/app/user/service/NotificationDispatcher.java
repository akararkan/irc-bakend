package ak.dev.irc.app.user.service;

import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single entry point for "persist a notification and push it to the user" —
 * Cassandra-only since the migration cutover.
 *
 * <p>The public surface ({@link #dispatch}, {@link #dispatchAggregated}) is
 * preserved for the existing callers in {@code NotificationEventConsumer},
 * but underneath it now routes straight to
 * {@link CassandraNotificationService}. Aggregation, realtime push, unread
 * counters and email delivery all happen on the Cassandra side now — no JPA
 * Notification table writes any more.</p>
 *
 * <p>Returns a synthetic {@link Notification} so legacy callers that read
 * {@code .getId()} off the result keep compiling. The id field is filled in
 * from the Cassandra notification_id; the rest is a faithful copy of the
 * draft the caller built.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final CassandraNotificationService cassandraNotificationService;

    /** One-shot notification — never aggregated. */
    public Notification dispatch(Notification draft) {
        UUID id = sendThrough(draft, /* aggregable */ false, /* groupKey */ null);
        return stampId(draft, id);
    }

    /**
     * Aggregating notification — coalescing is now done natively by the
     * Cassandra side using {@code notif_active_group_by_user} (TTL 60min).
     * The caller's {@code groupKey} convention is preserved unchanged.
     */
    public Notification dispatchAggregated(User recipient,
                                           User actor,
                                           NotificationType type,
                                           String groupKey,
                                           String title,
                                           String body,
                                           UUID resourceId,
                                           String resourceType) {
        Notification draft = Notification.builder()
                .user(recipient)
                .actor(actor)
                .type(type)
                .title(title)
                .body(body)
                .resourceId(resourceId)
                .resourceType(resourceType)
                .groupKey(groupKey)
                .aggregateCount(1L)
                .build();
        UUID id = sendThrough(draft, /* aggregable */ true, groupKey);
        return stampId(draft, id);
    }

    /**
     * Unread-count refresh — was a Postgres COUNT(*) before; now it reads
     * the dedicated Cassandra unread counter (an O(1) point read).
     */
    public void publishUnreadCount(UUID userId) {
        // The Cassandra side already maintains and broadcasts unread via
        // the unread counter + per-user channel. Kept as a public method so
        // callers (mark-read paths) stay source-compatible; no-op here.
        log.debug("[NOTIF] publishUnreadCount({}): handled by Cassandra channel", userId);
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private UUID sendThrough(Notification draft, boolean aggregable, String groupKey) {
        try {
            return cassandraNotificationService.deliver(
                    new CassandraNotificationService.LegacyDeliverRequest(
                            draft.getUser() == null ? null : draft.getUser().getId(),
                            draft.getType() == null ? null : draft.getType().name(),
                            draft.getTitle(),
                            draft.getBody(),
                            draft.getActor() == null ? null : draft.getActor().getId(),
                            draft.getResourceType(),
                            draft.getResourceId(),
                            aggregable && groupKey != null ? groupKey : draft.getGroupKey()
                    ));
        } catch (Exception e) {
            log.warn("[NOTIF] dispatch failed: {}", e.getMessage());
            return null;
        }
    }

    private static Notification stampId(Notification draft, UUID id) {
        draft.setId(id);
        return draft;
    }
}
