package ak.dev.irc.app.common.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                Minimal In-Process TTL Cache (pure JDK)                   │
 * │                                                                          │
 * │  A {@link ConcurrentHashMap} plus a stored expiry timestamp, checked     │
 * │  lazily on read — no background eviction thread. Every other cache in    │
 * │  {@code ak.dev.irc.app.common.cache} ({@link RateLimiter}, {@link        │
 * │  DedupGuard}, {@link CounterCache}, {@link IdempotencyFilter}) is Redis-  │
 * │  backed, which is the right call for state that must be shared/durable   │
 * │  across instances. This one is deliberately NOT Redis-backed: its        │
 * │  callers are in-process hot loops (a reaction/gift tap allowed up to     │
 * │  30/10s or 10/10s per user) where even a Redis round trip is overhead    │
 * │  worth avoiding, and the cached data (a recipient list, a path           │
 * │  resolution) tolerates a few seconds of staleness per-instance just      │
 * │  fine — see the TTL rationale on each call site.                        │
 * │                                                                          │
 * │  Concurrency: a miss/expiry race lets two threads both invoke the        │
 * │  loader for the same key — accepted at this scale (loaders here are      │
 * │  cheap, idempotent reads) rather than paying for a per-key lock.         │
 * │  {@code null} is a valid cached value: a loader that legitimately        │
 * │  resolves to "nothing" still gets an entry, so a repeat miss (e.g. a     │
 * │  bogus/expired path) doesn't re-hit the DB every call either.            │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
public final class TtlCache<K, V> {

    private final long ttlNanos;
    private final ConcurrentMap<K, Entry<V>> store = new ConcurrentHashMap<>();

    public TtlCache(Duration ttl) {
        this.ttlNanos = ttl.toNanos();
    }

    /**
     * Cache-aside read: returns the live value if present and unexpired,
     * otherwise calls {@code loader}, stores the result (including a
     * {@code null} result), and returns it.
     */
    public V get(K key, Supplier<V> loader) {
        long now = System.nanoTime();
        Entry<V> e = store.get(key);
        if (e != null && now < e.expiresAtNanos) return e.value;
        V value = loader.get();
        store.put(key, new Entry<>(value, now + ttlNanos));
        return value;
    }

    /** Evict a key so the next {@link #get} is a guaranteed fresh load. Call this
     *  right after a write that the next read must observe immediately, rather
     *  than relying on the TTL alone. */
    public void invalidate(K key) {
        store.remove(key);
    }

    private record Entry<V>(V value, long expiresAtNanos) {}
}
