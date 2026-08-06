package ak.dev.irc.app.media.service;

import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.media.entity.MediaQuota;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaQuotaRepository;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Daily per-role upload quota enforcement (admin media-storage.md §8),
 * checked at upload-intent time — before any presigned URL is handed out,
 * so a capped user costs zero storage traffic.
 *
 * <p>Two indexed queries against {@code media_assets (owner_id, created_at)}
 * per intent: today's row count and today's declared-byte sum. Roles without
 * an enabled quota row (ADMIN, staff tiers) skip both reads entirely after
 * one PK lookup on the tiny {@code media_quotas} table.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaQuotaService {

    private static final long GB = 1024L * 1024 * 1024;

    private final MediaQuotaRepository quotaRepository;
    private final MediaAssetRepository assetRepository;
    private final UserRepository userRepository;

    /** Seed the documented defaults once; admins tune them via the API after. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaults() {
        try {
            seed(Role.USER, 100, 2 * GB);
            seed(Role.RESEARCHER, 200, 8 * GB);
            seed(Role.SCHOLAR, 200, 8 * GB);
            // ADMIN / MODERATOR / SUPPORT / ANALYST: no row → unlimited.
        } catch (Exception e) {
            log.warn("[MEDIA-QUOTA] default seeding failed: {}", e.getMessage());
        }
    }

    private void seed(Role role, int uploads, long bytes) {
        if (quotaRepository.existsById(role)) return;
        quotaRepository.save(MediaQuota.builder()
                .role(role).dailyUploads(uploads).dailyBytes(bytes).enabled(true).build());
    }

    /**
     * Throws 429 {@code MEDIA_QUOTA_EXCEEDED} when this intent would push the
     * owner past their role's daily count or byte budget. Fail-open on any
     * internal error — quota bookkeeping must never block uploads outright.
     */
    public void enforce(UUID ownerId, long declaredBytes) {
        try {
            Role role = userRepository.roleOf(ownerId).orElse(null);
            if (role == null) return;
            MediaQuota quota = quotaRepository.findById(role).orElse(null);
            if (quota == null || !quota.isEnabled()) return;

            LocalDateTime midnight = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
            long uploadsToday = assetRepository.countByOwnerSince(ownerId, midnight);
            if (uploadsToday >= quota.getDailyUploads()) {
                throw quotaError("uploads", quota.getDailyUploads() + " uploads", role);
            }
            long bytesToday = assetRepository.sumOriginalBytesSince(ownerId, midnight);
            if (bytesToday + Math.max(0, declaredBytes) > quota.getDailyBytes()) {
                throw quotaError("bytes", (quota.getDailyBytes() / (1024 * 1024)) + " MB", role);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[MEDIA-QUOTA] enforcement check failed for {} — allowing: {}",
                    ownerId, e.getMessage());
        }
    }

    private AppException quotaError(String dimension, String budget, Role role) {
        return new AppException(
                "Daily upload quota reached (" + budget + " per day for your account tier). "
                        + "The quota resets at midnight UTC.",
                HttpStatus.TOO_MANY_REQUESTS,
                "MEDIA_QUOTA_EXCEEDED",
                Map.of("dimension", dimension, "role", role.name(), "resetsAt", "00:00 UTC"));
    }
}
