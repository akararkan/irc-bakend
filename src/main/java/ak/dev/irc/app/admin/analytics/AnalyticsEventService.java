package ak.dev.irc.app.admin.analytics;

import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The unified event store (analytics-kpis.md §6.2): {@code analytics_events},
 * day-bucketed with a 16-way shard so no partition grows unbounded, 400-day
 * TTL (rollups are the long-term record). Writers are fail-open one-statement
 * inserts — analytics must never fail or slow a user request. Props carry
 * typed-by-convention small values, NEVER free-text content (privacy rule
 * §12).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsEventService {

    static final int SHARDS = 16;

    private final CqlOperations cqlOperations;
    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTable() {
        try {
            session.execute("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_events (
                        event_day   text,
                        shard       tinyint,
                        event_time  timestamp,
                        event_id    timeuuid,
                        event_type  text,
                        actor_id    uuid,
                        target_type text,
                        target_id   text,
                        props       map<text,text>,
                        PRIMARY KEY ((event_day, shard), event_time, event_id)
                    ) WITH CLUSTERING ORDER BY (event_time DESC, event_id ASC)
                      AND default_time_to_live = 34560000
                    """.formatted(keyspace));
            log.info("[ANALYTICS] analytics_events table ensured (16 shards, 400d TTL)");
        } catch (Exception e) {
            log.warn("[ANALYTICS] analytics_events bootstrap failed: {}", e.getMessage());
        }
    }

    /** Fail-open event record — one INSERT, sharded, TTL'd at the table. */
    public void record(String eventType, UUID actorId, String targetType, String targetId,
                       Map<String, String> props) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            byte shard = (byte) ThreadLocalRandom.current().nextInt(SHARDS);
            cqlOperations.execute(
                    "INSERT INTO analytics_events "
                            + "(event_day, shard, event_time, event_id, event_type, actor_id, "
                            + "target_type, target_id, props) "
                            + "VALUES (?, ?, toTimestamp(now()), now(), ?, ?, ?, ?, ?)",
                    today.toString(), shard, eventType, actorId, targetType, targetId,
                    props == null ? Map.of() : props);
        } catch (Exception e) {
            log.debug("[ANALYTICS] event record {} failed: {}", eventType, e.getMessage());
        }
    }

    /** Per-type counts for one UTC day — the rollup job's source scan (16 bounded partitions). */
    public Map<String, Long> countsByTypeForDay(LocalDate day) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int shard = 0; shard < SHARDS; shard++) {
            try {
                var rows = cqlOperations.queryForList(
                        "SELECT event_type FROM analytics_events WHERE event_day = ? AND shard = ?",
                        day.toString(), (byte) shard);
                for (Map<String, Object> row : rows) {
                    counts.merge(String.valueOf(row.get("event_type")), 1L, Long::sum);
                }
            } catch (Exception e) {
                log.debug("[ANALYTICS] day scan {} shard {} failed: {}", day, shard, e.getMessage());
            }
        }
        return counts;
    }

    /**
     * Raw sample for taxonomy debugging (§9 — step-up-gated at the endpoint;
     * individual behavioral traces). Bounded: ≤ limit rows across the day's
     * 16 shard partitions.
     */
    public List<Map<String, Object>> sample(LocalDate day, String eventType, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int perShard = Math.max(1, limit / SHARDS + 1);
        for (int shard = 0; shard < SHARDS && out.size() < limit; shard++) {
            try {
                var rows = cqlOperations.queryForList(
                        "SELECT event_time, event_type, actor_id, target_type, target_id, props "
                                + "FROM analytics_events WHERE event_day = ? AND shard = ? LIMIT ?",
                        day.toString(), (byte) shard, perShard * 4);
                for (Map<String, Object> row : rows) {
                    if (eventType != null && !eventType.isBlank()
                            && !eventType.equals(String.valueOf(row.get("event_type")))) {
                        continue;
                    }
                    out.add(row);
                    if (out.size() >= limit) break;
                }
            } catch (Exception e) {
                log.debug("[ANALYTICS] sample shard {} failed: {}", shard, e.getMessage());
            }
        }
        return out;
    }
}
