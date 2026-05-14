package ak.dev.irc.app.qna.realtime;

import ak.dev.irc.app.qna.repository.QuestionViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Mirrors {@code PostViewTracker} for QnA questions. Authenticated viewers
 * count once per question forever (durable {@code question_views} ledger);
 * anonymous viewers fall back to a 1h Redis dedupe on the request fingerprint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionViewTracker {

    private static final Duration ANON_DEDUPE_WINDOW = Duration.ofHours(1);
    private static final String   ANON_KEY_PREFIX    = "irc:qview:anon:";

    private final StringRedisTemplate     redisTemplate;
    private final QuestionViewRepository  questionViewRepository;

    public boolean shouldCount(UUID questionId, UUID userId, String viewerKey) {
        if (userId != null) {
            try {
                int inserted = questionViewRepository.tryRecord(questionId, userId);
                return inserted == 1;
            } catch (DataIntegrityViolationException duplicate) {
                return false;
            } catch (Exception ex) {
                log.warn("[QNA-VIEW-LEDGER] tryRecord failed for question={} user={}: {}",
                        questionId, userId, ex.getMessage());
                return anonShouldCount(questionId, userId.toString());
            }
        }
        return anonShouldCount(questionId, viewerKey);
    }

    /** Backwards-compat overload. */
    public boolean shouldCount(UUID questionId, String viewerKey) {
        return shouldCount(questionId, null, viewerKey);
    }

    private boolean anonShouldCount(UUID questionId, String viewerKey) {
        if (viewerKey == null || viewerKey.isBlank()) viewerKey = "anon";
        String key = ANON_KEY_PREFIX + questionId + ":" + viewerKey;
        try {
            Boolean fresh = redisTemplate.opsForValue().setIfAbsent(key, "1", ANON_DEDUPE_WINDOW);
            return Boolean.TRUE.equals(fresh);
        } catch (Exception ex) {
            log.debug("[QNA-VIEW-TRACKER] Redis unavailable, counting without dedupe: {}", ex.getMessage());
            return true;
        }
    }
}
