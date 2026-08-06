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
    private final ChatSettingsService chatSettings;

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

    /** Online-now count for the admin overview tile — a bounded SCAN over the
     *  30s-TTL presence keys (users-roles.md §6 proxy). Capped at 100k keys. */
    public long onlineNowCount() {
        long count = 0;
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(PRESENCE_PREFIX + "*").count(1000).build())) {
            while (cursor.hasNext() && count < 100_000) {
                cursor.next();
                count++;
            }
        } catch (Exception e) {
            log.debug("[PRESENCE] online-now scan failed: {}", e.getMessage());
            return -1;   // sentinel: Redis unreachable, not "zero users online"
        }
        return count;
    }

    /** The online subset of a batch — one MGET instead of a per-user round-trip. */
    public java.util.Set<UUID> onlineAmong(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return java.util.Set.of();
        List<UUID> ids = new ArrayList<>(userIds);
        try {
            List<String> vals = redis.opsForValue()
                    .multiGet(ids.stream().map(id -> PRESENCE_PREFIX + id).toList());
            java.util.Set<UUID> online = new java.util.HashSet<>();
            if (vals != null) {
                for (int i = 0; i < ids.size(); i++) {
                    if (vals.get(i) != null) online.add(ids.get(i));
                }
            }
            return online;
        } catch (Exception e) {
            return java.util.Set.of(); // fail closed = offline, same as isOnline
        }
    }

    /** Last-seen for a batch — one MGET; absent/garbled entries yield no value. */
    private java.util.Map<UUID, Long> lastSeenAmong(List<UUID> ids) {
        java.util.Map<UUID, Long> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        try {
            List<String> vals = redis.opsForValue()
                    .multiGet(ids.stream().map(id -> LASTSEEN_PREFIX + id).toList());
            if (vals != null) {
                for (int i = 0; i < ids.size(); i++) {
                    String v = vals.get(i);
                    if (v != null) {
                        try { out.put(ids.get(i), Long.parseLong(v)); } catch (NumberFormatException ignored) { }
                    }
                }
            }
        } catch (Exception ignored) { /* best-effort */ }
        return out;
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
        List<UUID> ids = new ArrayList<>(userIds);
        java.util.Set<UUID> online = onlineAmong(ids);
        java.util.Map<UUID, Long> lastSeen = lastSeenAmong(
                ids.stream().filter(id -> !online.contains(id)).toList());
        List<PresenceResponse> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            out.add(online.contains(id)
                    ? new PresenceResponse(id, "online", null)
                    : new PresenceResponse(id, "offline", lastSeen.get(id)));
        }
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
        // Last-seen is symmetric: if the VIEWER hides theirs, they can't see others';
        // and a target who hides theirs is never shown with a last-seen time.
        boolean viewerHidesLastSeen = viewerId != null && !chatSettings.lastSeenVisible(viewerId);
        java.util.Set<UUID> targetsHidingLastSeen = viewerHidesLastSeen
                ? new java.util.HashSet<>(userIds) : chatSettings.lastSeenHiddenAmong(userIds);
        // Batched Redis reads (one MGET each for presence + last-seen) instead of
        // two round-trips per user.
        List<UUID> visible = userIds.stream().filter(id -> !blocked.contains(id)).toList();
        java.util.Set<UUID> online = onlineAmong(visible);
        java.util.Map<UUID, Long> lastSeen = lastSeenAmong(
                visible.stream().filter(id -> !online.contains(id)).toList());
        List<PresenceResponse> out = new ArrayList<>(userIds.size());
        for (UUID id : userIds) {
            if (blocked.contains(id)) {
                out.add(new PresenceResponse(id, "offline", null));
            } else if (online.contains(id)) {
                out.add(new PresenceResponse(id, "online", null));
            } else {
                // Suppress the last-seen timestamp (keep online/offline status).
                out.add(new PresenceResponse(id, "offline",
                        targetsHidingLastSeen.contains(id) ? null : lastSeen.get(id)));
            }
        }
        return out;
    }
}
