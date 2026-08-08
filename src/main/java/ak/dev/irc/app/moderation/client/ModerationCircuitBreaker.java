package ak.dev.irc.app.moderation.client;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A minimal sliding-window circuit breaker for calls into the inference
 * container (MODERATION_ROADMAP.md §7.4).
 *
 * <p>Resilience4j is what the roadmap sketches, but this project has no such
 * dependency and builds offline, so this is the hand-rolled equivalent — the
 * same shape the codebase already uses for Cassandra fan-out writes in
 * {@code FeedTimelineService}. It is deliberately small: what §5.6 actually
 * needs is a reliable, cheap answer to "is the model answering right now?", so a
 * slow content-create path fails fast to the fallback policy instead of every
 * request paying the full timeout.</p>
 *
 * <p>Thread-safe and lock-free. The window is a ring of booleans; a race can
 * mis-count by one or two outcomes near the boundary, which is irrelevant for a
 * percentage-based trip decision and much cheaper than synchronising the hot
 * path.</p>
 */
@Slf4j
public class ModerationCircuitBreaker {

    private final String name;
    private final int windowSize;
    private final int failureRatePercent;
    private final long openMillis;

    private final AtomicReferenceArray<Boolean> window;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong(0);

    public ModerationCircuitBreaker(String name, int windowSize, int failureRatePercent, long openMillis) {
        this.name = name;
        this.windowSize = Math.max(4, windowSize);
        this.failureRatePercent = Math.min(100, Math.max(1, failureRatePercent));
        this.openMillis = Math.max(1000, openMillis);
        this.window = new AtomicReferenceArray<>(this.windowSize);
    }

    /**
     * @return true when a call may proceed. An open breaker returns false until
     *         the cool-down elapses, then closes and clears its window — traffic
     *         resumes, and if the container is still broken the breaker re-trips
     *         once enough fresh failures accumulate.
     *
     *         <p>Deliberately a reset rather than a strict single-probe half-open.
     *         Serialising every caller behind one probe would need a lock on the
     *         hot path of content creation, and the cost of getting it "wrong" is
     *         one cool-down window of failed calls that each fail fast against a
     *         dead socket — cheaper than the contention.</p>
     */
    public boolean allowRequest() {
        long opened = openedAt.get();
        if (opened == 0L) return true;
        if (System.currentTimeMillis() - opened < openMillis) return false;
        // Cool-down elapsed: the first caller to win this CAS clears the window,
        // and everyone flows again until the failure rate trips it a second time.
        if (openedAt.compareAndSet(opened, 0L)) {
            reset();
            log.info("[MODERATION-CB] {} closed after cool-down — retrying", name);
        }
        return true;
    }

    public void recordSuccess() {
        record(true);
    }

    public void recordFailure() {
        record(false);
        maybeTrip();
    }

    public boolean open() {
        long opened = openedAt.get();
        return opened != 0L && System.currentTimeMillis() - opened < openMillis;
    }

    public String state() {
        return open() ? "OPEN" : "CLOSED";
    }

    private void record(boolean success) {
        int index = Math.floorMod(cursor.getAndIncrement(), windowSize);
        window.set(index, success);
    }

    private void maybeTrip() {
        int observed = 0;
        int failures = 0;
        for (int i = 0; i < windowSize; i++) {
            Boolean outcome = window.get(i);
            if (outcome == null) continue;
            observed++;
            if (!outcome) failures++;
        }
        // Don't trip on a half-empty window — two failures on startup are not a
        // 100% failure rate, they are two failures.
        if (observed < Math.max(4, windowSize / 4)) return;
        if (failures * 100 / observed >= failureRatePercent
                && openedAt.compareAndSet(0L, System.currentTimeMillis())) {
            log.warn("[MODERATION-CB] {} OPEN — {}/{} recent calls failed; "
                            + "content will follow the configured fallback policy for {}ms",
                    name, failures, observed, openMillis);
        }
    }

    private void reset() {
        for (int i = 0; i < windowSize; i++) {
            window.set(i, null);
        }
    }
}
