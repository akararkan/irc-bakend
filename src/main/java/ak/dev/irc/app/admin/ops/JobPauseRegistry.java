package ak.dev.irc.app.admin.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Redis-flag pause switch for scheduled jobs (operations.md §4.3): the flag
 * {@code ops:job-paused:{name}} suppresses the SCHEDULED run of a job whose
 * body checks {@link #isPaused}. Manual triggers via
 * {@code POST /admin/ops/jobs/{key}/run} deliberately bypass the pause —
 * that is how you test a paused job before resuming it.
 *
 * <p>Where the scheduled body is the same method a manual trigger invokes,
 * the pause blocks both — the trigger endpoint reports {@code JOB_PAUSED}
 * so the admin resumes first. The analytics rollup/backfill admin endpoints
 * call the job body directly and deliberately bypass the pause.</p>
 *
 * <p>Fail-open: if Redis is unreachable the job runs. A pause switch must
 * never become a way for an outage to silently stop retention/GDPR sweeps.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPauseRegistry {

    private static final String PREFIX = "ops:job-paused:";

    /** Jobs whose scheduled bodies honor the pause flag. */
    public static final Set<String> PAUSABLE = Set.of(
            "retention-sweep", "log-alert-sweep",
            "analytics-daily-rollup", "analytics-weekly-cohorts", "analytics-anomaly-scan",
            "trending-rebuild", "trending-digest",
            "notification-cleanup", "account-purge", "research-scheduled-publish",
            // Automated moderation (docs/moderation/MODERATION_ROADMAP.md §5.6, §12.4).
            // Pausing the SLA sweeper stops held content from being force-resolved —
            // useful while the inference container is being rolled — but it also
            // means nothing leaves PENDING until it resumes.
            "moderation-sla-sweep", "moderation-training-poll");

    private final StringRedisTemplate redis;

    public boolean isPaused(String jobName) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jobName));
        } catch (Exception e) {
            return false;   // Redis down → jobs keep running
        }
    }

    public void pause(String jobName) {
        redis.opsForValue().set(PREFIX + jobName, "1");
    }

    public void resume(String jobName) {
        redis.delete(PREFIX + jobName);
    }

    /** name → paused? for the ops board. */
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String name : PAUSABLE) out.put(name, isPaused(name));
        return out;
    }
}
