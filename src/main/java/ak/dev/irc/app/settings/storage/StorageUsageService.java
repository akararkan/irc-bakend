package ak.dev.irc.app.settings.storage;

import ak.dev.irc.app.media.repository.MediaAssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side storage footprint (spec §15). Computed from
 * {@code media_assets.stored_bytes} — a {@code SUM} over an indexed column,
 * never an object-store {@code LIST} (which is slow and billed) — and cached for
 * an hour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageUsageService {

    private final MediaAssetRepository assetRepo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public record StorageUsage(long totalBytes, Map<String, Long> byType) {}

    @Transactional(readOnly = true)
    public StorageUsage usage(UUID userId) {
        StorageUsage cached = readCache(userId);
        if (cached != null) return cached;

        long total = assetRepo.sumStoredBytes(userId);
        Map<String, Long> byType = new LinkedHashMap<>();
        for (var row : assetRepo.sumStoredBytesByType(userId)) {
            byType.put(row.getType().name(), row.getBytes());
        }
        StorageUsage usage = new StorageUsage(total, byType);
        writeCache(userId, usage);
        return usage;
    }

    private StorageUsage readCache(UUID userId) {
        try {
            String json = redis.opsForValue().get(key(userId));
            return json == null ? null : objectMapper.readValue(json, StorageUsage.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeCache(UUID userId, StorageUsage usage) {
        try {
            redis.opsForValue().set(key(userId), objectMapper.writeValueAsString(usage), Duration.ofHours(1));
        } catch (Exception ex) {
            log.debug("[STORAGE-USAGE] cache write failed for {}: {}", userId, ex.getMessage());
        }
    }

    private String key(UUID userId) {
        return "storage:usage:" + userId;
    }
}
