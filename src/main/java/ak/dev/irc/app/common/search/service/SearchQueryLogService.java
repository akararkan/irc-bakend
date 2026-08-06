package ak.dev.irc.app.common.search.service;

import ak.dev.irc.app.common.search.service.GlobalSearchService.EntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anonymous search-query telemetry (search-ops.md §7): what people search
 * for, and which searches return nothing (the content-gap signal).
 *
 * <p>Two sinks, both deliberately WITHOUT user ids (§12 privacy contract —
 * per-user search history is a product feature, not an admin one):</p>
 * <ul>
 *   <li><b>Redis top-N</b> — {@code ZINCRBY irc:search:top:{scope}:{yyyyMMdd}}
 *       per normalized query (plus {@code irc:search:zero:…} when the query
 *       returned 0 hits). Keys expire after 8 days: today + a 7-day window.</li>
 *   <li><b>Cassandra {@code search_queries_by_bucket}</b> — day-bucketed raw
 *       stream with a 90-day TTL, for offline taxonomy analysis.</li>
 * </ul>
 *
 * <p>Every write is async + fail-open: telemetry must never slow or fail a
 * user's search.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchQueryLogService {

    private static final int TTL_SECONDS = 90 * 24 * 3600;   // Cassandra raw stream
    private static final Duration REDIS_TTL = Duration.ofDays(8);
    private static final int MAX_QUERY_LEN = 120;

    private final StringRedisTemplate redis;
    @Autowired(required = false)
    private CqlOperations cqlOperations;

    private volatile boolean tableReady = false;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTable() {
        if (cqlOperations == null) return;
        try {
            cqlOperations.execute("""
                    CREATE TABLE IF NOT EXISTS search_queries_by_bucket (
                        bucket text,
                        ts timeuuid,
                        query text,
                        scope text,
                        result_count int,
                        degraded boolean,
                        PRIMARY KEY (bucket, ts)
                    ) WITH CLUSTERING ORDER BY (ts DESC)
                      AND default_time_to_live = """ + TTL_SECONDS);
            tableReady = true;
        } catch (Exception e) {
            log.warn("[SEARCH-LOG] search_queries_by_bucket ensure failed: {}", e.getMessage());
        }
    }

    /** Fire-and-forget record of one head search (cursor pages are not re-counted). */
    @Async
    public void record(String rawQuery, Set<EntityType> types, int resultCount, boolean degraded) {
        String q = normalize(rawQuery);
        if (q == null) return;
        String scope = scopeOf(types);
        String day = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
        try {
            bump("irc:search:top:" + scope + ":" + day, q);
            if (scope.equals("ALL")) {
                // scoped searches also count toward the global board
            } else {
                bump("irc:search:top:ALL:" + day, q);
            }
            if (resultCount == 0 && !degraded) {
                bump("irc:search:zero:" + scope + ":" + day, q);
                if (!scope.equals("ALL")) bump("irc:search:zero:ALL:" + day, q);
            }
        } catch (Exception e) {
            log.debug("[SEARCH-LOG] redis bump failed: {}", e.getMessage());
        }
        if (tableReady) {
            try {
                cqlOperations.execute(
                        "INSERT INTO search_queries_by_bucket "
                                + "(bucket, ts, query, scope, result_count, degraded) "
                                + "VALUES (?, now(), ?, ?, ?, ?)",
                        day, q, scope, resultCount, degraded);
            } catch (Exception e) {
                log.debug("[SEARCH-LOG] cassandra append failed: {}", e.getMessage());
            }
        }
    }

    private void bump(String key, String member) {
        redis.opsForZSet().incrementScore(key, member, 1);
        redis.expire(key, REDIS_TTL);
    }

    // ── Admin reads ─────────────────────────────────────────────────────

    /** Top queries over the last {@code days} (≤7), merged across day keys. */
    public List<Map<String, Object>> topQueries(String scope, int days, int limit, boolean zeroOnly) {
        String prefix = zeroOnly ? "irc:search:zero:" : "irc:search:top:";
        Map<String, Double> merged = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int span = Math.max(1, Math.min(days, 7));
        for (int i = 0; i < span; i++) {
            String key = prefix + normalizeScope(scope) + ":"
                    + today.minusDays(i).toString().replace("-", "");
            try {
                var entries = redis.opsForZSet().reverseRangeWithScores(key, 0, 499);
                if (entries == null) continue;
                for (var e : entries) {
                    if (e.getValue() == null) continue;
                    merged.merge(e.getValue(), e.getScore() == null ? 0 : e.getScore(), Double::sum);
                }
            } catch (Exception e) {
                log.debug("[SEARCH-LOG] read {} failed: {}", key, e.getMessage());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        merged.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, Math.min(limit, 200)))
                .forEach(e -> out.add(Map.of("query", e.getKey(), "count", e.getValue().longValue())));
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Lowercase, collapse whitespace, cap length; null when empty/garbage. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String q = raw.trim().toLowerCase().replaceAll("\\s+", " ");
        if (q.isEmpty()) return null;
        return q.length() > MAX_QUERY_LEN ? q.substring(0, MAX_QUERY_LEN) : q;
    }

    private static String scopeOf(Set<EntityType> types) {
        if (types == null || types.isEmpty()) return "ALL";
        if (types.size() == 1) return types.iterator().next().name();
        return "MULTI";
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "ALL";
        String s = scope.trim().toUpperCase();
        for (EntityType t : EntityType.values()) {
            if (t.name().equals(s)) return s;
        }
        return s.equals("MULTI") ? "MULTI" : "ALL";
    }
}
