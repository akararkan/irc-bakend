package ak.dev.irc.app.common.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Shared retry-once helper for every Elasticsearch call in the app.
 *
 * <p>The ES client pools HTTP connections; periodically the server (or a load
 * balancer in front of it) closes an idle connection and the next request to
 * pick that socket up sees {@code Connection is closed} /
 * {@code ConnectionClosedException}. The right fix is the pool-tuning
 * customizer in {@code ElasticsearchConfig} (caps idle keep-alive and TTL
 * so dead connections don't sit in the pool), but a race window remains where
 * the pool hands out a connection the server killed milliseconds ago. This
 * helper retries that single class of failure exactly once — by then the pool
 * has evicted the bad connection and the second attempt picks a fresh one.</p>
 *
 * <p>Used by every {@code *SearchService.indexAsync / deleteAsync} and by
 * {@code GlobalSearchService.search / searchCursor}. Any other exception
 * propagates unchanged (caller decides whether to swallow or log).</p>
 */
public final class EsRetry {

    private static final Logger log = LoggerFactory.getLogger(EsRetry.class);

    private EsRetry() {}

    /**
     * Run {@code action} once; if it fails with a stale-connection error,
     * try once more. Any non-stale exception is rethrown unchanged so the
     * caller can decide whether to swallow it.
     *
     * @param action the operation (typically a search/save/delete call)
     * @param label  short identifier used in the recovery DEBUG log
     */
    public static void run(Runnable action, String label) {
        try {
            action.run();
        } catch (RuntimeException first) {
            if (!isStaleConnection(first)) throw first;
            try {
                action.run();
                log.debug("{} recovered after retry (stale pooled connection)", label);
            } catch (RuntimeException second) {
                // Caller's try/catch will see the second failure; preserve it.
                throw second;
            }
        }
    }

    /**
     * Same as {@link #run(Runnable, String)} but for value-returning
     * suppliers. Returns the value from whichever attempt succeeded.
     */
    public static <T> T call(Supplier<T> action, String label) {
        try {
            return action.get();
        } catch (RuntimeException first) {
            if (!isStaleConnection(first)) throw first;
            T v = action.get();
            log.debug("{} recovered after retry (stale pooled connection)", label);
            return v;
        }
    }

    /**
     * True when a failure looks like the pool handed back a dead connection
     * — typically {@code "Connection is closed"}, {@code "Connection reset"},
     * or the Apache HTTP client's {@code ConnectionClosedException} /
     * {@code ConnectionShutdownException}.
     */
    public static boolean isStaleConnection(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("Connection is closed")
                    || msg.contains("Connection reset")
                    || msg.contains("Connection closed"))) {
                return true;
            }
            String cls = cur.getClass().getSimpleName();
            if ("ConnectionClosedException".equals(cls)
                    || "ConnectionShutdownException".equals(cls)) {
                return true;
            }
        }
        return false;
    }
}
