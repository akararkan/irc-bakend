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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The phase-3 engagement collector, pragmatic edition (analytics-kpis.md §6,
 * activity-engagement.md §6 "tee at the sink"): a date-bucketed metric store
 * — the thing the platform never had (every Cassandra counter is a lifetime
 * scalar). Two tables, created here:
 *
 * <pre>
 * analytics_metric_daily   ((metric, month), day) → counter   — per-day series
 * analytics_dau_by_day     ((day), user_id)                   — DAU via PK-dedup upsert
 * </pre>
 *
 * Writers are one-statement, fail-open tees (login success, activity-sink
 * writes) — telemetry must never slow or fail a user request. Population
 * metrics read THIS store; the private per-user activity partitions are never
 * mined (the privacy contract of analytics-kpis.md §12).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricDailyService {

    private final CqlOperations cqlOperations;
    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTables() {
        try {
            session.execute("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_metric_daily (
                        metric text, month text, day text, value counter,
                        PRIMARY KEY ((metric, month), day)
                    )""".formatted(keyspace));
            session.execute("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_dau_by_day (
                        day text, user_id uuid,
                        PRIMARY KEY (day, user_id)
                    )""".formatted(keyspace));
            // Rollup twin (§6.4): PLAIN bigint, not a counter — idempotent
            // overwrite makes re-runs safe; this is the long-term record while
            // the live counter table serves today-so-far.
            session.execute("""
                    CREATE TABLE IF NOT EXISTS %s.analytics_metric_rollup (
                        metric text, month text, day text, value bigint,
                        PRIMARY KEY ((metric, month), day)
                    )""".formatted(keyspace));
            log.info("[ANALYTICS] metric_daily + dau_by_day + metric_rollup tables ensured");
        } catch (Exception e) {
            log.warn("[ANALYTICS] table bootstrap failed (collector degraded): {}", e.getMessage());
        }
    }

    /** +1 on today's bucket for a metric. Fail-open, single counter UPDATE. */
    public void bump(String metric) {
        bumpBy(metric, 1);
    }

    public void bumpBy(String metric, long delta) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            cqlOperations.execute(
                    "UPDATE analytics_metric_daily SET value = value + ? "
                            + "WHERE metric = ? AND month = ? AND day = ?",
                    delta, metric, month(today), today.toString());
        } catch (Exception e) {
            log.debug("[ANALYTICS] bump {} failed: {}", metric, e.getMessage());
        }
    }

    /** Marks a user active today — PK upsert makes it idempotent per day. */
    public void markActive(UUID userId) {
        if (userId == null) return;
        try {
            cqlOperations.execute(
                    "INSERT INTO analytics_dau_by_day (day, user_id) VALUES (?, ?)",
                    LocalDate.now(ZoneOffset.UTC).toString(), userId);
        } catch (Exception e) {
            log.debug("[ANALYTICS] markActive failed: {}", e.getMessage());
        }
    }

    /** Per-day values of one metric over the trailing window (missing days = 0). */
    public Map<String, Long> series(String metric, int days) {
        Map<String, Long> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            out.put(today.minusDays(i).toString(), 0L);
        }
        try {
            LocalDate from = today.minusDays(days - 1);
            // A window spans at most (days/28)+2 month partitions.
            java.util.Set<String> months = new java.util.LinkedHashSet<>();
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                months.add(month(d));
            }
            for (String m : months) {
                var rows = cqlOperations.queryForList(
                        "SELECT day, value FROM analytics_metric_daily WHERE metric = ? AND month = ?",
                        metric, m);
                for (Map<String, Object> row : rows) {
                    String day = String.valueOf(row.get("day"));
                    if (out.containsKey(day)) {
                        out.put(day, ((Number) row.get("value")).longValue());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ANALYTICS] series {} failed: {}", metric, e.getMessage());
        }
        return out;
    }

    /** DAU per day over the trailing window (bounded partition counts). */
    public Map<String, Long> dauSeries(int days) {
        Map<String, Long> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            try {
                Long count = cqlOperations.queryForObject(
                        "SELECT COUNT(*) FROM analytics_dau_by_day WHERE day = ?",
                        Long.class, day);
                out.put(day, count == null ? 0L : count);
            } catch (Exception e) {
                out.put(day, 0L);
            }
        }
        return out;
    }

    /**
     * MAU: distinct active users across the trailing {@code days} — a true
     * cross-day dedup over the DAU partitions (bounded: one partition read per
     * day, accumulated into one set). Returns -1 when Cassandra is unreachable
     * so the tile can say "unavailable" instead of lying with 0.
     */
    public long distinctActiveOver(int days) {
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean anyRead = false;
        for (int i = 0; i < Math.max(1, Math.min(days, 90)); i++) {
            try {
                var rows = cqlOperations.queryForList(
                        "SELECT user_id FROM analytics_dau_by_day WHERE day = ?",
                        today.minusDays(i).toString());
                anyRead = true;
                for (Map<String, Object> row : rows) {
                    Object id = row.get("user_id");
                    if (id instanceof java.util.UUID uuid) seen.add(uuid);
                }
            } catch (Exception e) {
                log.debug("[ANALYTICS] MAU day read failed: {}", e.getMessage());
            }
        }
        return anyRead ? seen.size() : -1;
    }

    // ── Rollup layer (§6.4) ─────────────────────────────────────────────

    /** Idempotent rollup write — plain-column overwrite, safe to re-run. */
    public void writeRollup(String metric, LocalDate day, long value) {
        try {
            cqlOperations.execute(
                    "INSERT INTO analytics_metric_rollup (metric, month, day, value) VALUES (?, ?, ?, ?)",
                    metric, month(day), day.toString(), value);
        } catch (Exception e) {
            log.debug("[ANALYTICS] rollup write {} {} failed: {}", metric, day, e.getMessage());
        }
    }

    /**
     * Merged series: rollup value wins where present (the durable record),
     * live counter fills the gaps (today-so-far and not-yet-rolled days).
     */
    public Map<String, Long> mergedSeries(String metric, int days) {
        Map<String, Long> out = series(metric, days);   // live counters as the base
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate from = today.minusDays(days - 1);
            java.util.Set<String> months = new java.util.LinkedHashSet<>();
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                months.add(month(d));
            }
            for (String m : months) {
                var rows = cqlOperations.queryForList(
                        "SELECT day, value FROM analytics_metric_rollup WHERE metric = ? AND month = ?",
                        metric, m);
                for (Map<String, Object> row : rows) {
                    String day = String.valueOf(row.get("day"));
                    if (out.containsKey(day)) {
                        out.put(day, ((Number) row.get("value")).longValue());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ANALYTICS] merged series {} failed: {}", metric, e.getMessage());
        }
        return out;
    }

    /** One day's live-counter values across every metric partition is not
     *  readable (counter table is metric-partitioned) — the rollup job reads
     *  per-metric instead via {@link #series}. */
    public long liveValueFor(String metric, LocalDate day) {
        try {
            Long v = cqlOperations.queryForObject(
                    "SELECT value FROM analytics_metric_daily WHERE metric = ? AND month = ? AND day = ?",
                    Long.class, metric, month(day), day.toString());
            return v == null ? 0L : v;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String month(LocalDate d) {
        return d.getYear() + "-" + String.format("%02d", d.getMonthValue());
    }
}
