package ak.dev.irc.app.chat.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory fast path for the WebRTC signal relay — the hottest endpoint in the
 * calls feature (one hit per SDP/ICE frame, dozens per call setup). A call's
 * participant set is immutable after {@code initiate} (accept/decline/leave only
 * flip {@code state}, and the relay is state-agnostic), so once a route is cached
 * the only thing that can go stale is the call ending. Staleness is bounded two
 * ways: {@code invalidate} on every local end-transition, and a short TTL that
 * covers a call ended on another instance. A stale hit merely blind-relays a
 * frame to someone who was in the call moments ago — harmless — while a signal
 * for an <i>unknown</i> call always misses and falls back to the DB checks.
 */
@Component
public class CallSignalRouteCache {

    /** Upper bound on relaying into a call that already ended elsewhere. */
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    public record Route(UUID conversationId, Set<UUID> participantIds, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() - expiresAtNanos > 0;
        }
    }

    private final ConcurrentHashMap<UUID, Route> routes = new ConcurrentHashMap<>();

    /** The cached route, or {@code null} on miss/expiry (expired entries are evicted). */
    public Route get(UUID callId) {
        Route r = routes.get(callId);
        if (r == null) return null;
        if (r.expired()) {
            routes.remove(callId, r);
            return null;
        }
        return r;
    }

    public void put(UUID callId, UUID conversationId, Collection<UUID> participantIds) {
        routes.put(callId, new Route(conversationId, Set.copyOf(participantIds),
                System.nanoTime() + TTL_NANOS));
    }

    /**
     * Cache the route only once the surrounding transaction commits (a rollback
     * must not leave a route for a call that never existed); immediate when no
     * transaction is active.
     */
    public void primeAfterCommit(UUID callId, UUID conversationId, Collection<UUID> participantIds) {
        List<UUID> ids = List.copyOf(participantIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    put(callId, conversationId, ids);
                }
            });
        } else {
            put(callId, conversationId, ids);
        }
    }

    public void invalidate(UUID callId) {
        routes.remove(callId);
    }

    /** Drop expired routes so long-dead calls never accumulate. */
    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpired() {
        routes.values().removeIf(Route::expired);
    }
}
