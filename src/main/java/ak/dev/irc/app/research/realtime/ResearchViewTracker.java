package ak.dev.irc.app.research.realtime;

import ak.dev.irc.app.research.repository.ResearchViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Mirrors {@code PostViewTracker} for research papers. Authenticated viewers
 * count once per research forever (durable {@code research_views} ledger);
 * anonymous viewers fall back to a 1h Redis dedupe on the request fingerprint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchViewTracker {

    private static final Duration ANON_DEDUPE_WINDOW = Duration.ofHours(1);
    private static final String   ANON_KEY_PREFIX    = "irc:rview:anon:";

    private final StringRedisTemplate     redisTemplate;
    private final ResearchViewRepository  researchViewRepository;

    public boolean shouldCount(UUID researchId, UUID userId, String viewerKey) {
        if (userId != null) {
            try {
                int inserted = researchViewRepository.tryRecord(researchId, userId);
                return inserted == 1;
            } catch (DataIntegrityViolationException duplicate) {
                return false;
            } catch (Exception ex) {
                log.warn("[RESEARCH-VIEW-LEDGER] tryRecord failed for research={} user={}: {}",
                        researchId, userId, ex.getMessage());
                return anonShouldCount(researchId, userId.toString());
            }
        }
        return anonShouldCount(researchId, viewerKey);
    }

    /** Backwards-compat overload. */
    public boolean shouldCount(UUID researchId, String viewerKey) {
        return shouldCount(researchId, null, viewerKey);
    }

    private boolean anonShouldCount(UUID researchId, String viewerKey) {
        if (viewerKey == null || viewerKey.isBlank()) viewerKey = "anon";
        String key = ANON_KEY_PREFIX + researchId + ":" + viewerKey;
        try {
            Boolean fresh = redisTemplate.opsForValue().setIfAbsent(key, "1", ANON_DEDUPE_WINDOW);
            return Boolean.TRUE.equals(fresh);
        } catch (Exception ex) {
            log.debug("[RESEARCH-VIEW-TRACKER] Redis unavailable, counting without dedupe: {}", ex.getMessage());
            return true;
        }
    }
}
