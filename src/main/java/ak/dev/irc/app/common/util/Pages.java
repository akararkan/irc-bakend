package ak.dev.irc.app.common.util;

/**
 * Page-size clamping for read endpoints.
 *
 * <p>Every Cassandra read here flows the client-supplied page size straight
 * into {@code LIMIT :pageSize}, and hydrated feeds additionally fan the page
 * into {@code IN}-clause bulk loads. Without a cap, {@code ?pageSize=1000000}
 * forces a giant single-partition slice plus an IN over as many partitions —
 * unbounded latency, coordinator pressure, and heap. Clamp at the controller
 * boundary so no repository ever sees an unbounded size.</p>
 */
public final class Pages {

    /** Hard ceiling for any single page of results. */
    public static final int MAX_PAGE_SIZE = 100;

    private Pages() {}

    /** Clamps a requested page size into [1, {@value #MAX_PAGE_SIZE}]. */
    public static int clamp(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }
}
