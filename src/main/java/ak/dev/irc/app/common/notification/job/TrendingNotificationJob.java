package ak.dev.irc.app.common.notification.job;

import ak.dev.irc.app.common.messages.CommonMessages;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.entity.TrendingTagEntity;
import ak.dev.irc.app.common.tag.repository.TrendingTagRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService.DeliverRequest;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * X-style daily "trending in scholarship" notification.
 *
 * <p><strong>Cadence.</strong> Runs once a day (default 09:00 UTC). The
 * combination of cron + per-user group key
 * ({@code TRENDING_DIGEST:{yyyy-MM-dd}}) guarantees at most ONE digest per
 * user per day even if the job is re-fired manually.</p>
 *
 * <p><strong>Signal source.</strong> Top trending tags from the
 * {@link ContentType#QUESTION} and {@link ContentType#RESEARCH} scopes are
 * inherently scholar/researcher content — only those roles can publish in
 * those surfaces (see role gates in {@code QuestionServiceImpl} /
 * {@code ResearchServiceImpl}). No separate per-role counter table needed.</p>
 *
 * <p><strong>Anti-spam.</strong> Three layers:
 * <ol>
 *   <li>Per-user opt-out — {@code user.emailTrendingEnabled} (default true).
 *       Inbox push is still delivered; only the email side is muted.</li>
 *   <li>Min usage-count floor — tags with fewer than
 *       {@value #MIN_USAGE_FLOOR} uses are dropped. No notifications about
 *       niche content.</li>
 *   <li>Empty-list short-circuit — when there's nothing trending above the
 *       floor, the job logs and exits. Users are never woken for nothing.</li>
 * </ol></p>
 *
 * <p>Disable entirely with {@code irc.trending.notifications.enabled=false}
 * (e.g. during a launch where the catalogue is too small to be meaningful).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingNotificationJob {

    /** Top N tags pulled from each contributing scope before merging. */
    private static final int  TOP_PER_SCOPE   = 5;
    /** Final digest size. */
    private static final int  DIGEST_SIZE     = 5;
    /** Tags with fewer than this many uses are dropped before composing. */
    private static final long MIN_USAGE_FLOOR = 3L;
    /** Users per fan-out page (keyset). */
    private static final int  USER_PAGE_SIZE  = 500;
    /** Safety cap so a runaway scan can't fan out forever. */
    private static final int  MAX_PAGES       = 2_000;

    private final TrendingTagRepository       trendingRepo;
    private final UserRepository              userRepository;
    private final CassandraNotificationService notifications;
    private final ak.dev.irc.app.admin.ops.JobRunRecorder jobRunRecorder;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;

    @Value("${irc.trending.notifications.enabled:true}")
    private boolean enabled;

    /**
     * Daily fire at 09:00 UTC. Override with
     * {@code irc.trending.notifications.cron} for environment-specific tuning.
     */
    @Scheduled(cron = "${irc.trending.notifications.cron:0 0 9 * * *}", zone = "UTC")
    public void fireDailyDigest() {
        if (jobPause.isPaused("trending-digest")) return;
        jobRunRecorder.record("trending-digest", null, () -> {
            if (!enabled) {
                log.debug("[TRENDING] job disabled by config — skipping");
                return ak.dev.irc.app.admin.ops.JobRunRecorder.JobStats.NONE;
            }
            List<TrendingTagEntity> digest = buildDigest();
            if (digest.isEmpty()) {
                log.info("[TRENDING] no qualifying trending tags today (min={}) — skipping fan-out",
                        MIN_USAGE_FLOOR);
                return ak.dev.irc.app.admin.ops.JobRunRecorder.JobStats.NONE;
            }

            String today    = LocalDate.now(ZoneOffset.UTC).toString();
            String groupKey = "TRENDING_DIGEST:" + today;
            String body     = composeBody(digest);
            String title    = CommonMessages.NOTIF_TRENDING_DIGEST_TITLE;

            long sent = fanOut(groupKey, title, body);
            log.info("[TRENDING] dispatched digest [{}] to {} active users — tags={}",
                    today, sent, digest.stream().map(TrendingTagEntity::getTag).toList());
            return new ak.dev.irc.app.admin.ops.JobRunRecorder.JobStats((int) Math.min(sent, Integer.MAX_VALUE), 0);
        });
    }

    // ── Digest composition ─────────────────────────────────────────────────

    /**
     * Pull top tags from each scholarly scope (questions + research),
     * dedupe, keep the highest usageCount per tag, drop sub-floor entries,
     * and trim to {@link #DIGEST_SIZE}.
     */
    private List<TrendingTagEntity> buildDigest() {
        Map<String, TrendingTagEntity> byTag = new LinkedHashMap<>();
        for (String scope : List.of(ContentType.QUESTION.name(),
                                    ContentType.RESEARCH.name())) {
            List<TrendingTagEntity> rows;
            try {
                rows = trendingRepo.topForScope(scope, TOP_PER_SCOPE);
            } catch (Exception e) {
                log.warn("[TRENDING] could not read scope {}: {}", scope, e.getMessage());
                continue;
            }
            for (TrendingTagEntity t : rows) {
                if (t == null || t.getTag() == null) continue;
                long count = t.getUsageCount() == null ? 0L : t.getUsageCount();
                if (count < MIN_USAGE_FLOOR) continue;
                byTag.merge(t.getTag(), t, (existing, incoming) -> {
                    long a = existing.getUsageCount() == null ? 0L : existing.getUsageCount();
                    long b = incoming.getUsageCount() == null ? 0L : incoming.getUsageCount();
                    return b > a ? incoming : existing;
                });
            }
        }
        List<TrendingTagEntity> sorted = new ArrayList<>(byTag.values());
        sorted.sort(Comparator.comparingLong(
                (TrendingTagEntity t) -> t.getUsageCount() == null ? 0L : t.getUsageCount())
                .reversed());
        if (sorted.size() > DIGEST_SIZE) sorted = sorted.subList(0, DIGEST_SIZE);
        return sorted;
    }

    /** Human-readable body — kept short enough for mobile push surfaces. */
    private String composeBody(List<TrendingTagEntity> digest) {
        StringBuilder sb = new StringBuilder("Hot tags from scholars and researchers: ");
        for (int i = 0; i < digest.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('#').append(digest.get(i).getTag());
        }
        sb.append('.');
        return sb.toString();
    }

    // ── Fan-out ─────────────────────────────────────────────────────────────

    private long fanOut(String groupKey, String title, String body) {
        long sent  = 0;
        UUID after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<UUID> batch = userRepository.findActiveUserIdsAfter(
                    after, PageRequest.of(0, USER_PAGE_SIZE));
            if (batch.isEmpty()) break;
            for (UUID userId : batch) {
                notifications.deliverAsync(new DeliverRequest(
                        userId,
                        NotificationKind.TRENDING_DIGEST,
                        title,
                        body,
                        /* actorId       */ null,
                        /* resourceType  */ "Trending",
                        /* resourceId    */ null,
                        groupKey));
                sent++;
            }
            after = batch.get(batch.size() - 1);
            if (batch.size() < USER_PAGE_SIZE) break;
        }
        return sent;
    }

    /** Exposed for ops — manual trigger when you want to test the pipeline. */
    public void runNow() {
        Instant start = Instant.now();
        fireDailyDigest();
        log.info("[TRENDING] manual run completed in {} ms",
                java.time.Duration.between(start, Instant.now()).toMillis());
    }
}
