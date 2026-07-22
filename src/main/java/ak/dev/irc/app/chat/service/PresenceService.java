package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.response.PresenceResponse;
import ak.dev.irc.app.common.service.SocialGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Online/offline presence, pure Redis with heartbeat + TTL. A key
 * {@code chat:presence:{userId}} is refreshed while the user's SSE stream is
 * alive (and on each heartbeat ping); when it expires the user is offline.
 * {@code chat:lastseen:{userId}} keeps the last-seen epoch for offline display.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);
    private static final String PRESENCE_PREFIX = "chat:presence:";
    private static final String LASTSEEN_PREFIX = "chat:lastseen:";

    private final StringRedisTemplate redis;
    private final SocialGuard socialGuard;

    /** Mark the user online / refresh the TTL (called on stream open + heartbeat).
     *  Also stamps last-seen on every refresh so that when the presence key later
     *  expires (stream closed), the recorded last-seen is ≈ the final heartbeat —
     *  no explicit offline signal is needed, which keeps it correct across a
     *  multi-tab, multi-instance deployment. */
    public void markOnline(UUID userId) {
        try {
            redis.opsForValue().set(PRESENCE_PREFIX + userId, "online", PRESENCE_TTL);
            redis.opsForValue().set(LASTSEEN_PREFIX + userId, Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("[PRESENCE] set failed for {}: {}", userId, e.getMessage());
        }
    }

    /** Record last-seen on stream close (throttled by natural client cadence). */
    public void markOffline(UUID userId) {
        try {
            redis.delete(PRESENCE_PREFIX + userId);
            redis.opsForValue().set(LASTSEEN_PREFIX + userId, Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("[PRESENCE] clear failed for {}: {}", userId, e.getMessage());
        }
    }

    public boolean isOnline(UUID userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PRESENCE_PREFIX + userId));
        } catch (Exception e) {
            return false;
        }
    }

    public PresenceResponse presenceOf(UUID userId) {
        boolean online = isOnline(userId);
        Long lastSeen = null;
        if (!online) {
            try {
                String v = redis.opsForValue().get(LASTSEEN_PREFIX + userId);
                lastSeen = v == null ? null : Long.parseLong(v);
            } catch (Exception ignored) { /* best-effort */ }
        }
        return new PresenceResponse(userId, online ? "online" : "offline", lastSeen);
    }

    public List<PresenceResponse> presenceOf(Collection<UUID> userIds) {
        List<PresenceResponse> out = new ArrayList<>(userIds.size());
        for (UUID id : userIds) out.add(presenceOf(id));
        return out;
    }

    /**
     * Presence for a batch, from {@code viewerId}'s vantage point: a user in a
     * block relationship with the viewer is always reported offline with no
     * last-seen, so presence is never leaked across a block (design 05).
     */
    public List<PresenceResponse> presenceOf(Collection<UUID> userIds, UUID viewerId) {
        // One cached lookup of everyone in a block relationship with the viewer,
        // rather than a per-id block query.
        java.util.Set<UUID> blocked = viewerId == null ? java.util.Set.of()
                : new java.util.HashSet<>(socialGuard.findRelatedBlockedIds(viewerId));
        List<PresenceResponse> out = new ArrayList<>(userIds.size());
        for (UUID id : userIds) {
            if (blocked.contains(id)) {
                out.add(new PresenceResponse(id, "offline", null));
            } else {
                out.add(presenceOf(id));
            }
        }
        return out;
    }
}
