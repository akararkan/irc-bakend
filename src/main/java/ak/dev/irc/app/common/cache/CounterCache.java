package ak.dev.irc.app.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                  Redis-backed Hot Counter Cache                          │
 * │                                                                          │
 * │  Each entity (post, comment, research, research-comment, question,       │
 * │  answer) keeps its denormalised counters in a single Redis hash so a     │
 * │  feed-card render reads N fields in one round-trip instead of touching   │
 * │  Postgres per row.                                                       │
 * │                                                                          │
 * │  Strategy:  Write-through after DB commit. DB is source of truth; Redis  │
 * │  mirrors the post-update absolute values.  Reads use cache-aside with a  │
 * │  DB fallback that also rewarms the cache transparently.                  │
 * │                                                                          │
 * │  Concurrency:  HSET of an absolute value (computed from `em.refresh`)    │
 * │  is monotonically advanced by the natural ordering of DB commits, so     │
 * │  two concurrent reactors cannot leapfrog the broadcast count.            │
 * │                                                                          │
 * │  Scale:  designed for millions of entities. Each hash is 50–200 bytes;   │
 * │  a 30-day idle TTL keeps memory bounded by hot working-set, not by       │
 * │  lifetime traffic.  Cold reads pay one Postgres round-trip then warm.    │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterCache {

    /** One Redis key namespace per entity kind. Prefix is short to keep memory tight. */
    public enum Kind {
        POST("c:p:"),
        POST_COMMENT("c:pc:"),
        RESEARCH("c:r:"),
        RESEARCH_COMMENT("c:rc:"),
        QUESTION("c:q:"),
        ANSWER("c:a:");

        final String prefix;
        Kind(String prefix) { this.prefix = prefix; }
    }

    // ── Field codes (two letters, stable across kinds) ────────────────────
    public static final String F_REACTIONS  = "rx";
    public static final String F_COMMENTS   = "cm";
    public static final String F_REPLIES    = "rp";
    public static final String F_VIEWS      = "vw";
    public static final String F_SHARES     = "sh";
    public static final String F_SAVES      = "sv";
    public static final String F_DOWNLOADS  = "dl";
    public static final String F_CITATIONS  = "ct";
    public static final String F_ANSWERS    = "an";
    public static final String F_BEST_VOTES = "bv";

    /** 30-day idle TTL — touched on every write, so hot rows stay forever. */
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;

    private String key(Kind kind, UUID id) { return kind.prefix + id; }

    // ══════════════════════════════════════════════════════════════════════
    //  WRITE PATH (called after DB commit, using the freshly-refreshed entity)
    // ══════════════════════════════════════════════════════════════════════

    /** Write a single counter's absolute value. The DB-derived value is authoritative. */
    public void set(Kind kind, UUID id, String field, long value) {
        try {
            String k = key(kind, id);
            redis.opsForHash().put(k, field, String.valueOf(value));
            redis.expire(k, TTL);
        } catch (Exception ex) {
            log.debug("[CounterCache] set {} {}.{}={} failed: {}",
                    kind, id, field, value, ex.getMessage());
        }
    }

    /** Write multiple counters in one call (used to hydrate from DB after a cold miss). */
    public void setAll(Kind kind, UUID id, Map<String, Long> values) {
        if (values.isEmpty()) return;
        try {
            String k = key(kind, id);
            Map<String, String> str = new HashMap<>(values.size());
            values.forEach((f, v) -> str.put(f, String.valueOf(v)));
            redis.opsForHash().putAll(k, Map.copyOf(str));
            redis.expire(k, TTL);
        } catch (Exception ex) {
            log.debug("[CounterCache] setAll {} {} failed: {}", kind, id, ex.getMessage());
        }
    }

    /**
     * Atomic increment (HINCRBY). Used when the DB write is also a delta — keeps
     * Redis monotonic under concurrent reactors. Returns the post-increment value
     * (the broadcast can use this directly).
     */
    public Long incr(Kind kind, UUID id, String field, long delta) {
        try {
            String k = key(kind, id);
            Long v = redis.opsForHash().increment(k, field, delta);
            redis.expire(k, TTL);
            return v;
        } catch (Exception ex) {
            log.debug("[CounterCache] incr {} {}.{}+{} failed: {}",
                    kind, id, field, delta, ex.getMessage());
            return null;
        }
    }

    /** Drop the whole hash. Used when the underlying row is hard-deleted. */
    public void invalidate(Kind kind, UUID id) {
        try { redis.delete(key(kind, id)); }
        catch (Exception ex) { log.debug("[CounterCache] del failed: {}", ex.getMessage()); }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  READ PATH (called by mappers — cache-aside with DB fallback)
    // ══════════════════════════════════════════════════════════════════════

    /** Read all counters for one entity. Empty map → cache cold. */
    public Map<String, Long> get(Kind kind, UUID id) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(key(kind, id));
            if (raw.isEmpty()) return Collections.emptyMap();
            Map<String, Long> out = new HashMap<>(raw.size());
            raw.forEach((k, v) -> {
                try { out.put(k.toString(), Long.parseLong(v.toString())); }
                catch (NumberFormatException ignored) {}
            });
            return out;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    /**
     * Read one counter with a lazy DB fallback. If the cache is cold for this
     * field, calls {@code dbFallback}, populates Redis with the result, then
     * returns it. Subsequent reads hit Redis.
     */
    public long getOr(Kind kind, UUID id, String field, LongSupplier dbFallback) {
        try {
            Object v = redis.opsForHash().get(key(kind, id), field);
            if (v != null) {
                try { return Long.parseLong(v.toString()); }
                catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        long dbValue = dbFallback.getAsLong();
        set(kind, id, field, dbValue);
        return dbValue;
    }

    /**
     * Batched read for feed pages — 1 Redis round-trip for N entities instead of N.
     * IDs that miss the cache are returned with an empty inner map; the caller is
     * expected to populate them from their DB row and call {@link #setAll} to warm.
     */
    public Map<UUID, Map<String, Long>> getMany(Kind kind, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        List<UUID> list = new ArrayList<>(ids);
        try {
            List<Object> results = redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) (RedisConnection conn) -> {
                for (UUID id : list) {
                    byte[] rk = key(kind, id).getBytes(StandardCharsets.UTF_8);
                    conn.hashCommands().hGetAll(rk);
                }
                return null;
            });
            Map<UUID, Map<String, Long>> out = new HashMap<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object res = i < results.size() ? results.get(i) : null;
                Map<String, Long> fields = new HashMap<>();
                if (res instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> {
                        if (k != null && v != null) {
                            try { fields.put(k.toString(), Long.parseLong(v.toString())); }
                            catch (NumberFormatException ignored) {}
                        }
                    });
                }
                out.put(list.get(i), fields);
            }
            return out;
        } catch (Exception ex) {
            log.debug("[CounterCache] getMany {} ({} ids) failed: {}", kind, list.size(), ex.getMessage());
            Map<UUID, Map<String, Long>> empty = new HashMap<>(list.size());
            for (UUID id : list) empty.put(id, Collections.emptyMap());
            return empty;
        }
    }
}
