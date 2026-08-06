package ak.dev.irc.app.admin.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wrapper the business jobs (and manual triggers) call around their body so
 * every execution leaves a {@code job_runs} row. Best-effort by design —
 * ledger trouble must never fail a job.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobRunRecorder {

    private final JobRunRepository jobRunRepository;

    public record JobStats(int processed, int failed) {
        public static final JobStats NONE = new JobStats(0, 0);
    }

    @FunctionalInterface
    public interface JobBody {
        JobStats run() throws Exception;
    }

    public JobRun record(String jobName, UUID triggeredBy, JobBody body) {
        JobRun run = start(jobName, triggeredBy);
        try {
            JobStats stats = body.run();
            finish(run, stats == null ? JobStats.NONE : stats, null);
        } catch (Exception ex) {
            finish(run, JobStats.NONE, ex);
            log.warn("[JOB-RUN] {} failed: {}", jobName, ex.getMessage());
        }
        return run;
    }

    public JobRun start(String jobName, UUID triggeredBy) {
        try {
            return jobRunRepository.save(JobRun.builder()
                    .jobName(jobName)
                    .triggeredBy(triggeredBy)
                    .host(hostName())
                    .build());
        } catch (Exception e) {
            log.debug("[JOB-RUN] ledger start failed for {}: {}", jobName, e.getMessage());
            return JobRun.builder().jobName(jobName).startedAt(LocalDateTime.now()).build();
        }
    }

    public void finish(JobRun run, JobStats stats, Exception error) {
        try {
            run.setFinishedAt(LocalDateTime.now());
            run.setDurationMs(Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
            run.setItemsProcessed(stats.processed());
            run.setItemsFailed(stats.failed());
            if (error != null) {
                run.setOutcome(JobRun.Outcome.FAILED);
                String msg = String.valueOf(error.getMessage());
                run.setError(msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            } else {
                run.setOutcome(stats.failed() > 0 ? JobRun.Outcome.PARTIAL : JobRun.Outcome.SUCCESS);
            }
            if (run.getId() != null) {
                jobRunRepository.save(run);
            }
        } catch (Exception e) {
            log.debug("[JOB-RUN] ledger finish failed for {}: {}", run.getJobName(), e.getMessage());
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
