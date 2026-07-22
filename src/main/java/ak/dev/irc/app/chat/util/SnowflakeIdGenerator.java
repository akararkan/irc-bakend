package ak.dev.irc.app.chat.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Twitter-style <b>Snowflake</b> generator for 64-bit, time-sortable message IDs.
 *
 * <p>A message ID is the single most important primitive in the chat data model.
 * Encoding the creation time in the high bits buys three properties for free —
 * with <b>no</b> coordination, no central sequence, and no secondary sort:</p>
 * <ul>
 *   <li><b>Global uniqueness</b> — each node stamps its own {@code nodeId}.</li>
 *   <li><b>Time ordering</b> — sorting by ID is sorting by send time, so the
 *       Cassandra clustering key {@code message_id DESC} yields newest-first with
 *       zero extra columns.</li>
 *   <li><b>Free bucketing + pagination</b> — the timestamp (and therefore the
 *       partition {@code bucket}) is recoverable from the ID alone, and a page
 *       cursor is just {@code message_id < :cursor}.</li>
 * </ul>
 *
 * <pre>
 *  63                    22        12       0
 * ┌──┬────────────────────┬─────────┬─────────┐
 * │0 │  timestamp (41b)   │node(10b)│ seq(12b)│
 * └──┴────────────────────┴─────────┴─────────┘
 *    ms since CUSTOM_EPOCH   │         └─ per-ms sequence (0..4095)
 *                            └────────── worker / instance id (0..1023)
 * </pre>
 *
 * <p>4096 IDs/ms/node ≈ 4.1M IDs/s/node — far beyond any single-node send rate.
 * The sign bit is always 0 so IDs stay positive and compare correctly as signed
 * {@code long} (which is how Cassandra {@code bigint} and Postgres {@code bigint}
 * both order them).</p>
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /**
     * Fixed millisecond epoch — 2023-11-14T22:13:20Z. <b>Never change once live:</b>
     * every existing ID's embedded timestamp (and thus every stored message's
     * bucket) is measured from here. Chosen to sit safely in the past so the
     * 41-bit window (~69 years) runs to ~2093.
     */
    public static final long CUSTOM_EPOCH = 1_700_000_000_000L;

    private static final long NODE_BITS = 10L;
    private static final long SEQ_BITS  = 12L;

    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1; // 1023
    private static final long MAX_SEQUENCE = (1L << SEQ_BITS) - 1; // 4095

    private static final long NODE_SHIFT      = SEQ_BITS;              // 12
    private static final long TIMESTAMP_SHIFT = SEQ_BITS + NODE_BITS;  // 22

    /**
     * Instance id (0..1023). Set via {@code APP_NODE_ID} / {@code app.node-id};
     * when unset it is derived from the host name so a horizontally-scaled
     * deployment gets distinct-enough node ids without per-pod config.
     */
    @Value("${app.node-id:-1}")
    private long configuredNodeId;

    private long nodeId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    @PostConstruct
    void init() {
        this.nodeId = resolveNodeId(configuredNodeId);
        log.info("[SNOWFLAKE] initialised with nodeId={} (epoch={})", nodeId, CUSTOM_EPOCH);
    }

    private static long resolveNodeId(long configured) {
        if (configured >= 0) {
            return configured & MAX_NODE_ID;
        }
        // Derive a stable-per-host id from the machine name. Collisions across a
        // handful of pods are astronomically unlikely to also collide on the same
        // millisecond + sequence, so uniqueness holds in practice.
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return (host.hashCode() & 0x7fffffff) % (MAX_NODE_ID + 1);
        } catch (Exception e) {
            return (Thread.currentThread().hashCode() & 0x7fffffff) % (MAX_NODE_ID + 1);
        }
    }

    /**
     * Generates the next monotonic ID. Synchronised because the whole cost is a
     * few arithmetic ops on shared counters — contention is negligible next to a
     * Cassandra write, and it guarantees strict per-node monotonicity even under
     * a burst that fills a millisecond's 4096-slot sequence.
     */
    public synchronized long nextId() {
        long now = System.currentTimeMillis();

        if (now < lastTimestamp) {
            // Clock moved backwards (NTP step). Rather than mint a non-monotonic
            // id, pin to the last observed time and let the sequence advance —
            // ordering is preserved and the drift is at most a few ms.
            now = lastTimestamp;
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted for this ms — spin to the next millisecond.
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = now;

        return ((now - CUSTOM_EPOCH) << TIMESTAMP_SHIFT)
                | (nodeId << NODE_SHIFT)
                | sequence;
    }

    private static long waitNextMillis(long lastTimestamp) {
        long now = System.currentTimeMillis();
        while (now <= lastTimestamp) {
            now = System.currentTimeMillis();
        }
        return now;
    }

    /** Extracts the wall-clock creation time (epoch millis) embedded in an ID. */
    public static long timestampOf(long id) {
        return (id >>> TIMESTAMP_SHIFT) + CUSTOM_EPOCH;
    }

    /** The node that minted the ID (diagnostics only). */
    public static long nodeOf(long id) {
        return (id >>> NODE_SHIFT) & MAX_NODE_ID;
    }
}
