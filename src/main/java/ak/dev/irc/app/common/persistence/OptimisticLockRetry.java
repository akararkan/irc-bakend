package ak.dev.irc.app.common.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * In-tree retry wrapper for {@link OptimisticLockingFailureException}.
 *
 * <p>JPA's {@code @Version} optimistic-lock check rejects an update when
 * the row's version has moved since we read it. That's correct behavior
 * — the alternative would silently overwrite a concurrent writer's
 * changes — but for endpoints that are user-facing single-row mutations
 * (e.g. "upload a cover image", "set a video promo") a stale-read race
 * is recoverable: re-fetch the row, re-apply the change, save again.
 * This helper runs that loop for you.</p>
 *
 * <p>Why hand-rolled instead of {@code @Retryable} (Spring Retry):
 * Spring Retry isn't on the classpath and adding it for one call site
 * is overkill. Twenty lines of code beat a new dependency.</p>
 *
 * <p><b>Important — call this OUTSIDE the {@code @Transactional}
 * boundary.</b> The retry only helps if each attempt opens a fresh
 * transaction with a fresh entity read. Wrapping it around a service
 * method that itself starts a transaction (the typical Spring pattern)
 * gives you that automatically. Wrapping it INSIDE a transaction is a
 * no-op — the same transaction can't re-resolve a version conflict.</p>
 */
@Slf4j
public final class OptimisticLockRetry {

    private OptimisticLockRetry() {}

    /** Default attempts cap — first try + 2 retries = 3 total. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** Backoff baseline; actual sleep adds 0–100ms jitter to spread thundering herds. */
    public static final long DEFAULT_BASE_SLEEP_MS = 50L;

    /**
     * Runs {@code op}. On {@link OptimisticLockingFailureException} sleeps
     * a short randomized backoff and re-runs, up to {@code maxAttempts}
     * total. After the final failure the original exception is re-thrown
     * so the caller still sees the conflict.
     *
     * @param label       short label for log lines (e.g. {@code "cover-image"})
     * @param op          the operation to run; should open its own transaction
     * @param maxAttempts {@code 1} = no retry; default is {@link #DEFAULT_MAX_ATTEMPTS}
     */
    public static <T> T run(String label, Supplier<T> op, int maxAttempts) {
        if (maxAttempts < 1) maxAttempts = 1;
        OptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = op.get();
                if (attempt > 1) {
                    log.info("[RETRY] {} succeeded on attempt {}/{}", label, attempt, maxAttempts);
                }
                return result;
            } catch (OptimisticLockingFailureException ex) {
                last = ex;
                if (attempt == maxAttempts) {
                    log.warn("[RETRY] {} exhausted {}/{} attempts — giving up: {}",
                            label, attempt, maxAttempts, ex.getMessage());
                    break;
                }
                long sleep = DEFAULT_BASE_SLEEP_MS * attempt
                        + ThreadLocalRandom.current().nextLong(100L);
                log.debug("[RETRY] {} hit optimistic lock on attempt {}/{} — re-fetching in {}ms",
                        label, attempt, maxAttempts, sleep);
                try { Thread.sleep(sleep); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;   // honour interrupt — surface the original lock conflict
                }
            }
        }
        throw last;   // unreachable when maxAttempts < 1 (clamped above)
    }

    /** {@link #run(String, Supplier, int)} with {@link #DEFAULT_MAX_ATTEMPTS}. */
    public static <T> T run(String label, Supplier<T> op) {
        return run(label, op, DEFAULT_MAX_ATTEMPTS);
    }
}
