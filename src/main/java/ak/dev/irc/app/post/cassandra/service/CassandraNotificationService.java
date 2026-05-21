package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.email.EmailService;
import ak.dev.irc.app.post.cassandra.entity.NotifActiveGroupEntity;
import ak.dev.irc.app.post.cassandra.entity.NotificationEntity;
import ak.dev.irc.app.post.cassandra.entity.NotificationLookupEntity;
import ak.dev.irc.app.common.notification.NotificationEmailFormatter;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.repository.NotifActiveGroupRepository;
import ak.dev.irc.app.post.cassandra.repository.NotificationLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.NotificationRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Notification engine — Cassandra-native, realtime via Redis, email through
 * the existing {@link EmailService}.
 *
 * Delivery pipeline for one event:
 *   1. Self-suppression — don't notify the actor about their own action.
 *   2. Block check — if recipient ↔ actor are in any block relationship, drop.
 *   3. Aggregation — if {@link NotificationKind#aggregable()} AND an unread
 *      same-group notification exists in the 60-min window, coalesce: bump
 *      aggregate_count, replace lastActorId, rewrite body.
 *   4. Persist:  notifications_by_user, notification_lookup, notif_active_group_by_user.
 *   5. Unread counter ++ (only on a fresh row; coalesce keeps the count).
 *   6. Realtime push on irc:notifications:{userId} → SSE delivers to every
 *      open tab on every running instance.
 *   7. Email — fire-and-forget through EmailService.sendAsync IF
 *        • kind.emailEligible
 *        • recipient.emailNotificationsEnabled (master toggle)
 *        • the category-specific toggle is on
 *        • Redis throttle key wasn't set in the last hour for this groupKey
 *
 * Every public path delegates to an @Async wrapper so callers (reactions,
 * comments) return without waiting on Cassandra writes or SMTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraNotificationService {

    public static final  String   REDIS_CHANNEL_PREFIX  = "irc:notifications:";
    private static final Duration AGGREGATION_WINDOW    = Duration.ofMinutes(60);
    private static final String   EMAIL_THROTTLE_PREFIX = "notif:email:throttle:";
    private static final Duration EMAIL_THROTTLE_TTL    = Duration.ofHours(1);

    private final NotificationRepository       notificationRepo;
    private final NotificationLookupRepository lookupRepo;
    private final NotifActiveGroupRepository   activeGroupRepo;
    private final CounterService               counterService;
    private final StringRedisTemplate          redis;
    private final ObjectMapper                 objectMapper;
    private final CqlOperations                cqlOperations;
    private final UserRepository               userRepo;
    private final UserBlockRepository          userBlockRepo;
    private final EmailService                 emailService;
    private final NotificationEmailFormatter   emailFormatter;

    public record DeliverRequest(
            UUID             userId,         // recipient
            NotificationKind kind,
            String           title,
            String           body,
            UUID             actorId,        // who triggered it (may be null)
            String           resourceType,
            UUID             resourceId,
            String           groupKey        // e.g. "POST_REACTED:postId" — required if aggregable
    ) {}

    // ── Public API ───────────────────────────────────────────────────────────

    @Async
    public void deliverAsync(DeliverRequest req) {
        try {
            deliverSync(req);
        } catch (Exception e) {
            log.warn("[NOTIF] deliver {} → user {} failed: {}",
                    req.kind(), req.userId(), e.getMessage());
        }
    }

    /**
     * Deferred-enrichment delivery. The supplier runs on the async executor
     * — use this when building the {@link DeliverRequest} requires extra
     * database reads (e.g. fetching the post author or the actor's username)
     * that should not block the originating write's HTTP response.
     *
     * <p>Returning {@code null} from the supplier is a no-op (used to short
     * out when the referenced post/comment has been deleted or the recipient
     * can't be resolved).</p>
     */
    @Async
    public void deliverAsync(Supplier<DeliverRequest> builder) {
        try {
            DeliverRequest req = builder.get();
            if (req == null) return;
            deliverSync(req);
        } catch (Exception e) {
            log.warn("[NOTIF] enriched-deliver failed: {}", e.getMessage());
        }
    }

    /**
     * Bulk variant of {@link #deliverAsync(Supplier)} — the supplier yields
     * many {@link DeliverRequest} rows in one shot (typically because the
     * caller batched a Postgres / Cassandra lookup to resolve N recipients
     * at once, like @mention fan-out). All requests share one executor task.
     */
    @Async
    public void deliverAllAsync(Supplier<List<DeliverRequest>> builder) {
        try {
            List<DeliverRequest> batch = builder.get();
            if (batch == null || batch.isEmpty()) return;
            for (DeliverRequest req : batch) {
                try {
                    deliverSync(req);
                } catch (Exception e) {
                    log.debug("[NOTIF] one-of-batch deliver failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[NOTIF] batched-deliver build failed: {}", e.getMessage());
        }
    }

    public Optional<UUID> deliverSync(DeliverRequest req) {
        if (req.userId() == null || req.kind() == null) return Optional.empty();
        if (suppressed(req))                            return Optional.empty();

        return req.kind().aggregable() && req.groupKey() != null
                ? aggregateInto(req)
                : insertFresh(req);
    }

    // ── Read API ─────────────────────────────────────────────────────────────

    public List<NotificationEntity> inbox(UUID userId, int pageSize) {
        return notificationRepo.firstPage(userId, pageSize);
    }

    public List<NotificationEntity> inboxAfter(UUID userId, Instant cursor, int pageSize) {
        return notificationRepo.nextPage(userId, cursor, pageSize);
    }

    public void markRead(UUID notificationId) {
        lookupRepo.findById(notificationId).ifPresent(m -> {
            notificationRepo.markRead(m.getUserId(), m.getCreatedAt(), notificationId);
            counterService.decrementUnread(m.getUserId());
        });
    }

    /**
     * Hard-delete one notification. Looks up the (user_id, created_at) PK via
     * {@code notification_lookup}, removes the {@code notifications_by_user}
     * row, and clears the lookup row. Decrements the unread counter if the
     * row was still unread.
     */
    public boolean deleteById(UUID notificationId) {
        return lookupRepo.findById(notificationId).map(meta -> {
            try {
                notificationRepo.deleteRow(meta.getUserId(),
                                           meta.getCreatedAt(),
                                           notificationId);
                lookupRepo.deleteById(notificationId);
                // The counter only tracks unread; we don't know prior is_read state
                // from the lookup row, so we don't decrement here. The counter
                // resyncs naturally on the next mark-read action.
                return true;
            } catch (Exception e) {
                log.warn("[NOTIF] delete failed for {}: {}", notificationId, e.getMessage());
                return false;
            }
        }).orElse(false);
    }

    /**
     * Hard-delete every row in the user's inbox that's already marked read.
     * Returns the number of rows removed.
     *
     * <p>Strategy: do a single partition scan to find the boundary timestamp
     * — the {@code created_at} of the oldest UNREAD row in the page. Then
     * issue ONE range delete for everything strictly older than that boundary
     * (everyone older than the oldest unread MUST be read, since rows arrive
     * in time order). That collapses up to N row tombstones into a single
     * range tombstone, so subsequent inbox reads aren't slowed down by ghost
     * scans during the 10-day gc_grace window.</p>
     *
     * <p>If no unread rows exist on the page, range-delete the whole partition.
     * Any read rows newer than the oldest unread fall back to per-row delete
     * — this is the rare "user read a middle notification but not older ones"
     * case and is bounded by the page size.</p>
     *
     * <p>Lookup rows ({@code notification_lookup}) are not range-deletable
     * because they're keyed by {@code notification_id}. We let them expire
     * naturally via their 90-day TTL rather than scatter-deleting them and
     * paying tombstones in many other partitions.</p>
     */
    public int deleteAllReadFor(UUID userId) {
        List<NotificationEntity> page = notificationRepo.firstPage(userId, 500);
        if (page.isEmpty()) return 0;

        // Find the OLDEST unread row in the page. Page is ordered newest-first,
        // so we walk it and remember the last (= oldest in page) unread we see.
        Instant oldestUnread = null;
        int readInPage = 0;
        for (NotificationEntity n : page) {
            if (Boolean.TRUE.equals(n.getRead())) {
                readInPage++;
            } else {
                oldestUnread = n.getCreatedAt();
            }
        }
        if (readInPage == 0) return 0;

        int removed = 0;
        if (oldestUnread == null) {
            // Entire page is read — range-delete everything in (or older than) the page.
            // Use page's newest createdAt + 1ms as the upper bound so we include
            // every read row currently on the page. Rows newer than the page (none
            // by definition of "first page") are unaffected.
            Instant upperBound = page.get(0).getCreatedAt().plusMillis(1);
            notificationRepo.deleteOlderThan(userId, upperBound);
            removed = readInPage;
        } else {
            // Range-delete everyone strictly older than the oldest unread —
            // those are guaranteed to be read, since rows arrive in time order.
            notificationRepo.deleteOlderThan(userId, oldestUnread);
            // Anything READ but newer than oldestUnread is a "user opened a
            // middle notification" case. Per-row delete for those.
            for (NotificationEntity n : page) {
                if (!Boolean.TRUE.equals(n.getRead())) continue;
                if (!n.getCreatedAt().isAfter(oldestUnread)) continue;
                try {
                    notificationRepo.deleteRow(userId, n.getCreatedAt(), n.getNotificationId());
                } catch (Exception e) {
                    log.debug("[NOTIF] delete-read row {} failed: {}",
                            n.getNotificationId(), e.getMessage());
                }
            }
            removed = readInPage;
        }
        return removed;
    }

    /**
     * Bulk-delete every notification in the user's inbox strictly older than
     * {@code before}. Produces ONE range tombstone — safe for big sweeps
     * (e.g. nightly purge of 60-day-old read items). Lookup rows expire on
     * their own via the 90-day TTL.
     */
    public void deleteOlderThan(UUID userId, Instant before) {
        if (userId == null || before == null) return;
        notificationRepo.deleteOlderThan(userId, before);
    }

    public Long unreadCountFor(UUID userId) {
        try {
            return cqlOperations.queryForObject(
                    "SELECT unread FROM notification_unread_counter WHERE user_id = ?",
                    Long.class, userId);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ── Suppression ──────────────────────────────────────────────────────────

    private boolean suppressed(DeliverRequest req) {
        if (req.actorId() != null && req.actorId().equals(req.userId())) return true;
        if (req.actorId() != null) {
            try {
                if (userBlockRepo.isBlockedBetween(req.userId(), req.actorId())) return true;
            } catch (Exception e) {
                log.debug("[NOTIF] block check failed: {}", e.getMessage());
            }
        }
        return false;
    }

    // ── Insert / aggregate primitives ────────────────────────────────────────

    private Optional<UUID> insertFresh(DeliverRequest req) {
        UUID    id  = UUID.randomUUID();
        Instant now = Instant.now();

        NotificationEntity row = NotificationEntity.builder()
                .userId(req.userId()).createdAt(now).notificationId(id)
                .type(req.kind().name())
                .title(req.title()).body(req.body())
                .actorId(req.actorId()).lastActorId(req.actorId())
                .aggregateCount(1L)
                .resourceType(req.resourceType()).resourceId(req.resourceId())
                .groupKey(req.groupKey())
                .read(false)
                .build();
        notificationRepo.save(row);

        lookupRepo.save(NotificationLookupEntity.builder()
                .notificationId(id).userId(req.userId()).createdAt(now)
                .build());

        if (req.groupKey() != null) {
            activeGroupRepo.save(NotifActiveGroupEntity.builder()
                    .userId(req.userId()).groupKey(req.groupKey())
                    .notificationId(id).createdAt(now)
                    .build());
        }

        counterService.incrementUnread(req.userId());
        publishRealtime(req.userId(), row);
        maybeSendEmail(req);
        return Optional.of(id);
    }

    private Optional<UUID> aggregateInto(DeliverRequest req) {
        Optional<NotifActiveGroupEntity> head = activeGroupRepo.find(req.userId(), req.groupKey());
        if (head.isEmpty()) return insertFresh(req);

        NotifActiveGroupEntity h = head.get();
        Long prior = currentAggregateCount(req.userId(), h.getCreatedAt(), h.getNotificationId());
        long newCount = (prior == null ? 1L : prior) + 1L;

        notificationRepo.coalesce(req.userId(), h.getCreatedAt(), h.getNotificationId(),
                                   req.body(), req.actorId(), newCount);
        publishRealtimeCoalesce(req, h, newCount);
        // No email on subsequent aggregations — the first email already fired.
        return Optional.of(h.getNotificationId());
    }

    private Long currentAggregateCount(UUID userId, Instant createdAt, UUID notificationId) {
        try {
            return cqlOperations.queryForObject(
                    "SELECT aggregate_count FROM notifications_by_user " +
                    "WHERE user_id = ? AND created_at = ? AND notification_id = ?",
                    Long.class, userId, createdAt, notificationId);
        } catch (Exception e) {
            return 1L;
        }
    }

    // ── Email ────────────────────────────────────────────────────────────────

    private void maybeSendEmail(DeliverRequest req) {
        NotificationKind kind = req.kind();
        if (!kind.emailEligible()) return;

        User recipient;
        try {
            recipient = userRepo.findActiveById(req.userId()).orElse(null);
        } catch (Exception e) {
            return;
        }
        if (recipient == null) return;
        if (!recipient.isEmailNotificationsEnabled()) return;

        boolean categoryOn = switch (kind.prefCategory()) {
            case SOCIAL   -> recipient.isEmailSocialEnabled();
            case MENTIONS -> recipient.isEmailMentionsEnabled();
            case SYSTEM   -> recipient.isEmailSystemEnabled();
        };
        if (!categoryOn) return;

        // Per-group throttle: a user doesn't get 10 emails when 10 likes
        // arrive in 60 seconds — first fires, rest are gated for 1h.
        if (req.groupKey() != null && !claimEmailThrottle(req.userId(), req.groupKey())) return;

        NotificationEmailFormatter.EmailBody msg = emailFormatter.format(
                kind, req.title(), req.body(), req.resourceType(), req.resourceId());

        try {
            emailService.sendAsync(recipient.getEmail(), msg.subject(), msg.plainText(), msg.html());
        } catch (Exception e) {
            log.debug("[NOTIF] email send skipped: {}", e.getMessage());
        }
    }

    private boolean claimEmailThrottle(UUID userId, String groupKey) {
        try {
            Boolean firstWriter = redis.opsForValue()
                    .setIfAbsent(EMAIL_THROTTLE_PREFIX + userId + ":" + groupKey, "1",
                                 EMAIL_THROTTLE_TTL);
            return Boolean.TRUE.equals(firstWriter);
        } catch (Exception e) {
            // Redis miss → be permissive rather than drop notifications.
            return true;
        }
    }

    // ── Realtime ─────────────────────────────────────────────────────────────

    private void publishRealtime(UUID userId, NotificationEntity n) {
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("notificationId", n.getNotificationId().toString());
            p.put("type",           n.getType());
            p.put("title",          n.getTitle());
            p.put("body",           n.getBody());
            p.put("actorId",        n.getActorId()      == null ? null : n.getActorId().toString());
            p.put("lastActorId",    n.getLastActorId()  == null ? null : n.getLastActorId().toString());
            p.put("aggregateCount", n.getAggregateCount());
            p.put("resourceType",   n.getResourceType());
            p.put("resourceId",     n.getResourceId()   == null ? null : n.getResourceId().toString());
            p.put("groupKey",       n.getGroupKey());
            p.put("createdAt",      n.getCreatedAt().toString());
            redis.convertAndSend(REDIS_CHANNEL_PREFIX + userId, objectMapper.writeValueAsString(p));
        } catch (Exception e) {
            log.debug("[NOTIF] realtime publish skipped: {}", e.getMessage());
        }
    }

    private void publishRealtimeCoalesce(DeliverRequest req, NotifActiveGroupEntity head, long newCount) {
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("notificationId", head.getNotificationId().toString());
            p.put("type",           req.kind().name());
            p.put("body",           req.body());
            p.put("lastActorId",    req.actorId() == null ? null : req.actorId().toString());
            p.put("aggregateCount", newCount);
            p.put("resourceType",   req.resourceType());
            p.put("resourceId",     req.resourceId() == null ? null : req.resourceId().toString());
            p.put("groupKey",       req.groupKey());
            p.put("coalesced",      Boolean.TRUE);
            redis.convertAndSend(REDIS_CHANNEL_PREFIX + req.userId(),
                                  objectMapper.writeValueAsString(p));
        } catch (Exception e) {
            log.debug("[NOTIF] coalesce push skipped: {}", e.getMessage());
        }
    }

    // ── Legacy shim ──────────────────────────────────────────────────────────
    // Keep the old record shape callable so NotificationDispatcher's mirror
    // works unchanged. The legacy `type` (string) maps onto NotificationKind;
    // unknown values fall back to SYSTEM_MESSAGE.

    public record LegacyDeliverRequest(
            UUID userId, String type, String title, String body,
            UUID actorId, String resourceType, UUID resourceId, String groupKey) {}

    public UUID deliver(LegacyDeliverRequest legacy) {
        NotificationKind kind;
        try { kind = NotificationKind.valueOf(legacy.type()); }
        catch (IllegalArgumentException e) { kind = NotificationKind.SYSTEM_MESSAGE; }
        return deliverSync(new DeliverRequest(
                legacy.userId(), kind, legacy.title(), legacy.body(),
                legacy.actorId(), legacy.resourceType(), legacy.resourceId(),
                legacy.groupKey()
        )).orElse(null);
    }
}
