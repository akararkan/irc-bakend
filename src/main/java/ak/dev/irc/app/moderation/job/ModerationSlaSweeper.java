package ak.dev.irc.app.moderation.job;

import ak.dev.irc.app.admin.ops.JobPauseRegistry;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import ak.dev.irc.app.moderation.service.ContentModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The SLA sweeper of MODERATION_ROADMAP.md §5.6/§11 — the safety net that makes
 * "nothing stays stuck in limbo" true rather than aspirational.
 *
 * <p>It runs independently of the queue on purpose. If a broker message was lost,
 * or the inference container was down for the whole hold window, or the worker
 * dead-lettered the case, this is the code that still reaches a defined outcome.
 * It must therefore depend on nothing but the database.</p>
 *
 * <p>Three passes, cheapest first:</p>
 * <ol>
 *   <li><b>Retry</b> — cases still inside their window that the inline attempt
 *       missed. Most clear here, well before the deadline.</li>
 *   <li><b>Breach</b> — past the deadline with no verdict: apply the configured
 *       fallback policy (fail-closed to review, or fail-open + flag).</li>
 *   <li><b>Unapplied</b> — a verdict exists but the content flip failed. Left
 *       alone these are approved posts that never publish, which is the quietest
 *       and worst failure this system can have.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationSlaSweeper {

    private static final String JOB = "moderation-sla-sweep";

    /** Bounded per tick so one backlog spike cannot monopolise the scheduler pool. */
    private static final int RETRY_BATCH = 25;
    private static final int BREACH_BATCH = 50;
    private static final int APPLY_BATCH = 50;

    private final ModerationCaseRepository caseRepository;
    private final ContentModerationService moderationService;
    private final JobPauseRegistry jobPause;

    /**
     * Every few seconds, per §5.6. The scheduler pool is four threads shared with
     * SSE heartbeats, so each pass is capped and every case is isolated in its own
     * try/catch — one poisoned row must not stall the sweep for everything else.
     */
    @Scheduled(initialDelayString = "${app.moderation.sweeper.initial-delay-ms:20000}",
            fixedDelayString = "${app.moderation.sweeper.interval-ms:5000}")
    public void sweep() {
        if (jobPause.isPaused(JOB)) return;

        LocalDateTime now = LocalDateTime.now();
        int retried = retryInsideWindow(now);
        int breached = resolveBreaches(now);
        int applied = driveUnapplied();
        int recovered = recoverNeverScored();

        if (breached > 0 || applied > 0 || recovered > 0) {
            log.info("[MODERATION-SLA] retried={} breached={} re-applied={} outage-recovered={}",
                    retried, breached, applied, recovered);
        }
    }

    /**
     * Outage recovery: re-score cases that were dumped into the review queue by an
     * SLA breach without ever being scored.
     *
     * <p>Without this, an inference outage leaves a permanent scar — every piece of
     * content created while the container was down sits at "being reviewed"
     * forever, waiting on a human to hand-judge a backlog the model can settle in
     * milliseconds. The author just sees their post stuck. Cases that reached
     * review on a genuine borderline score are untouched: the model already had
     * its say and the human *is* the escalation.</p>
     *
     * <p>Cheap when there is nothing to do (one indexed query returning zero rows)
     * and self-limiting while the service is still down — the circuit breaker
     * fails each attempt fast and the rows simply stay put for the next tick.</p>
     */
    private int recoverNeverScored() {
        List<ModerationCase> stranded =
                caseRepository.findNeverScored(PageRequest.of(0, RETRY_BATCH));
        int count = 0;
        for (ModerationCase moderationCase : stranded) {
            try {
                if (moderationService.rescore(moderationCase.getId()) != null) count++;
            } catch (Exception ex) {
                log.debug("[MODERATION-SLA] outage recovery failed for case {}: {}",
                        moderationCase.getId(), ex.getMessage());
            }
        }
        return count;
    }

    private int retryInsideWindow(LocalDateTime now) {
        List<ModerationCase> retryable = caseRepository.findRetryable(
                now, moderationService.maxWorkerAttempts(), PageRequest.of(0, RETRY_BATCH));
        int count = 0;
        for (ModerationCase moderationCase : retryable) {
            try {
                moderationService.rescore(moderationCase.getId());
                count++;
            } catch (Exception ex) {
                log.debug("[MODERATION-SLA] retry failed for case {}: {}",
                        moderationCase.getId(), ex.getMessage());
            }
        }
        return count;
    }

    private int resolveBreaches(LocalDateTime now) {
        List<ModerationCase> breached =
                caseRepository.findBreachedHolds(now, PageRequest.of(0, BREACH_BATCH));
        int count = 0;
        for (ModerationCase moderationCase : breached) {
            try {
                // One last scoring attempt before falling back: the container may
                // have come up in the last second, and a real verdict always beats
                // a policy default.
                moderationService.rescore(moderationCase.getId());
                ModerationCase reloaded = caseRepository.findWithFields(moderationCase.getId())
                        .orElse(null);
                if (reloaded != null
                        && reloaded.getStatus() == ak.dev.irc.app.moderation.enums.ModerationStatus.PENDING) {
                    moderationService.applyFallback(reloaded);
                    count++;
                }
            } catch (Exception ex) {
                log.warn("[MODERATION-SLA] fallback failed for case {}: {}",
                        moderationCase.getId(), ex.getMessage());
            }
        }
        return count;
    }

    private int driveUnapplied() {
        List<ModerationCase> unapplied = caseRepository.findUnapplied(PageRequest.of(0, APPLY_BATCH));
        int count = 0;
        for (ModerationCase moderationCase : unapplied) {
            try {
                moderationService.applyIfNeeded(moderationCase);
                count++;
            } catch (Exception ex) {
                log.warn("[MODERATION-SLA] re-apply failed for case {}: {}",
                        moderationCase.getId(), ex.getMessage());
            }
        }
        return count;
    }
}
