package ak.dev.irc.app.settings.core.service;

import ak.dev.irc.app.settings.core.SettingsProperties;
import ak.dev.irc.app.settings.core.dto.SettingsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis cache for the fully-resolved settings object (spec §22.2). Loaded once
 * per request context, not per field access — the classic bug is a feed render
 * calling the settings service 200 times for 200 posts.
 *
 * <p>Fail-open like {@link ak.dev.irc.app.common.cache.CounterCache}: any Redis
 * error degrades to a DB read. Writes MUST call {@link #invalidate} explicitly.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsCache {

    private static final String PREFIX = "settings:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SettingsProperties props;

    public Optional<SettingsResponse> get(UUID userId) {
        try {
            String json = redis.opsForValue().get(PREFIX + userId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, SettingsResponse.class));
        } catch (Exception ex) {
            log.debug("[SETTINGS-CACHE] get miss/err for {}: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(UUID userId, SettingsResponse value) {
        try {
            redis.opsForValue().set(PREFIX + userId,
                    objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(props.getCacheTtlSeconds()));
        } catch (Exception ex) {
            log.debug("[SETTINGS-CACHE] put failed for {}: {}", userId, ex.getMessage());
        }
    }

    public void invalidate(UUID userId) {
        try {
            redis.delete(PREFIX + userId);
        } catch (Exception ex) {
            log.debug("[SETTINGS-CACHE] invalidate failed for {}: {}", userId, ex.getMessage());
        }
    }
}
