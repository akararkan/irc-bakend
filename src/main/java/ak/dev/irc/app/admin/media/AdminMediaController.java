package ak.dev.irc.app.admin.media;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.media.service.MediaProcessingService;
import ak.dev.irc.app.research.service.S3StorageService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Media pipeline oversight (blueprint §3.7, media-storage.md §5): the failed
 * queues nobody could see, admin retry (re-runs the in-process pipeline — the
 * Rabbit media queues are declared but have no producer or consumer), the
 * dedup-safe admin delete, the raw/ purge sweep the platform never had, and
 * platform storage totals.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/media")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMediaController {

    private final MediaAssetRepository assetRepository;
    private final MediaRenditionRepository renditionRepository;
    private final ak.dev.irc.app.media.repository.MediaQuotaRepository quotaRepository;
    private final MediaProcessingService processingService;
    private final S3StorageService storage;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminMediaRow(UUID id, UUID ownerId, String type, String status,
                                String mime, Long originalBytes, Long storedBytes,
                                Integer width, Integer height, Integer durationMs,
                                String requestedTier, String errorMessage,
                                LocalDateTime createdAt, LocalDateTime updatedAt,
                                LocalDateTime purgeOriginalAt) {

        static AdminMediaRow of(MediaAsset m) {
            return new AdminMediaRow(m.getId(), m.getOwnerId(),
                    m.getType() != null ? m.getType().name() : null,
                    m.getStatus() != null ? m.getStatus().name() : null,
                    m.getMime(), m.getOriginalBytes(), m.getStoredBytes(),
                    m.getWidth(), m.getHeight(), m.getDurationMs(),
                    m.getRequestedTier() != null ? m.getRequestedTier().name() : null,
                    m.getErrorMessage(), m.getCreatedAt(), m.getUpdatedAt(),
                    m.getPurgeOriginalAt());
        }
    }

    public record ReasonBody(@Size(max = 500) String reason) {
    }

    // ── browse / detail ─────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<AdminMediaRow>> browse(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25) Pageable pageable) {
        Page<MediaAsset> page = assetRepository.adminBrowse(
                parseStatus(status), parseType(type), ownerId, from, to,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize())));
        return ResponseEntity.ok(page.map(AdminMediaRow::of));
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID assetId) {
        MediaAsset asset = require(assetId);
        List<Map<String, Object>> renditions = new ArrayList<>();
        for (MediaRendition r : renditionRepository.findByIdMediaId(assetId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", r.getId().getLabel());
            row.put("objectKey", r.getObjectKey());
            row.put("url", r.getUrl());
            row.put("bytes", r.getBytes());
            row.put("width", r.getWidth());
            row.put("height", r.getHeight());
            row.put("mime", r.getMime());
            renditions.add(row);
        }
        long dedupSiblings = asset.getContentHash() == null ? 0
                : assetRepository.countByContentHashAndIdNot(asset.getContentHash(), assetId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asset", AdminMediaRow.of(asset));
        body.put("renditions", renditions);
        body.put("dedupSiblings", dedupSiblings);
        return ResponseEntity.ok(body);
    }

    // ── actions ─────────────────────────────────────────────────────────

    /**
     * Retry a failed asset by re-running the in-process pipeline on the
     * retained {@code raw/} original. 410-equivalent when the original is
     * already purged/missing.
     */
    @PostMapping("/{assetId}/reprocess")
    public ResponseEntity<Void> reprocess(@PathVariable UUID assetId) {
        MediaAsset asset = require(assetId);
        MediaStatus status = asset.getStatus();
        if (status != MediaStatus.FAILED_PROCESSING && status != MediaStatus.FAILED_VALIDATION
                && status != MediaStatus.FAILED_MODERATION && status != MediaStatus.PROCESSING) {
            throw new BadRequestException(
                    "Only failed (or stuck PROCESSING) assets can be reprocessed.",
                    "ASSET_NOT_RETRYABLE");
        }
        byte[] original;
        try (var stream = storage.getObject("raw/" + assetId).inputStream()) {
            original = stream.readAllBytes();
        } catch (Exception e) {
            throw new BadRequestException(
                    "The raw original is gone — this asset can no longer be reprocessed.",
                    "MEDIA_RAW_MISSING");
        }
        asset.setStatus(MediaStatus.PROCESSING);
        asset.setErrorMessage(null);
        assetRepository.save(asset);
        processingService.submit(assetId, original, asset.getType(), asset.getRequestedTier());
        adminAuditor.record(AuditOperation.UPDATE, "MediaAsset", assetId, "ADMIN_MEDIA_REPROCESS");
        return ResponseEntity.accepted().build();
    }

    /**
     * Admin takedown of an asset. Dedup-safe: shared object keys (dedup
     * references share renditions with the source) are deleted from storage
     * only when no other asset row still points at the same content hash —
     * the hazard the user-facing delete path has.
     */
    @DeleteMapping("/{assetId}")
    @RequiresStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID assetId,
                                       @RequestBody(required = false) ReasonBody body) {
        MediaAsset asset = require(assetId);
        boolean shared = asset.getContentHash() != null
                && assetRepository.countByContentHashAndIdNot(asset.getContentHash(), assetId) > 0;

        List<MediaRendition> renditions = renditionRepository.findByIdMediaId(assetId);
        if (!shared) {
            for (MediaRendition r : renditions) {
                try {
                    storage.delete(r.getObjectKey());
                } catch (Exception e) {
                    log.warn("[ADMIN-MEDIA] rendition object delete failed ({}): {}",
                            r.getObjectKey(), e.getMessage());
                }
            }
            try {
                storage.delete("raw/" + assetId);
            } catch (Exception ignored) {
            }
        }
        renditionRepository.deleteByIdMediaId(assetId);
        assetRepository.delete(asset);

        moderationRecorder.decision("MEDIA", assetId.toString(), "ADMIN_MEDIA_DELETE",
                body != null ? body.reason() : null, null,
                "sharedObjects=" + shared + ", renditions=" + renditions.size());
        adminAuditor.record(AuditOperation.DELETE, "MediaAsset", assetId,
                "ADMIN_MEDIA_DELETE", body != null ? body.reason() : null);
        return ResponseEntity.accepted().build();
    }

    /**
     * The raw/ purge sweep the platform never had (leaks L1+L2): originals
     * past their 7-day retention plus abandoned PENDING intents. Dry-run by
     * default.
     */
    @PostMapping("/purge-raw/run")
    public ResponseEntity<Map<String, Object>> purgeRaw(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        LocalDateTime now = LocalDateTime.now();
        List<MediaAsset> due = new ArrayList<>(assetRepository.findByPurgeOriginalAtBefore(now));
        // L2: PENDING intents older than 7d never got a purge_original_at at all.
        due.addAll(assetRepository.findByStatusAndCreatedAtBefore(
                MediaStatus.PENDING, now.minusDays(7)));

        int purged = 0;
        for (MediaAsset asset : due) {
            if (!dryRun) {
                try {
                    storage.delete("raw/" + asset.getId());
                    asset.setPurgeOriginalAt(null);
                    if (asset.getStatus() == MediaStatus.PENDING) {
                        asset.setStatus(MediaStatus.FAILED_VALIDATION);
                        asset.setErrorMessage("Abandoned upload intent purged by admin sweep.");
                    }
                    assetRepository.save(asset);
                    purged++;
                } catch (Exception e) {
                    log.warn("[ADMIN-MEDIA] raw purge of {} failed: {}",
                            asset.getId(), e.getMessage());
                }
            }
        }
        if (!dryRun) {
            adminAuditor.record(AuditOperation.DELETE, "MediaAsset", null,
                    "ADMIN_MEDIA_RAW_PURGE", "candidates=" + due.size() + ", purged=" + purged);
        }
        Map<String, Object> bodyOut = new LinkedHashMap<>();
        bodyOut.put("dryRun", dryRun);
        bodyOut.put("candidates", due.size());
        bodyOut.put("purged", purged);
        return ResponseEntity.ok(bodyOut);
    }

    /** Pipeline status funnel — the board's headline numbers. */
    @GetMapping("/status-summary")
    public ResponseEntity<Map<String, Long>> statusSummary() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (MediaStatus s : MediaStatus.values()) {
            out.put(s.name(), 0L);
        }
        for (Object[] row : assetRepository.countGroupedByStatus()) {
            out.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return ResponseEntity.ok(out);
    }

    // ── bucket reconcile (media-storage.md §7) ──────────────────────────

    /**
     * Lists bucket objects under {@code prefix} and reports orphans — objects
     * no rendition row references and no raw/{assetId} row explains. Dry-run
     * by default; {@code dryRun=false} deletes the orphans (step-up gated).
     * DB-but-not-bucket drift is the reverse direction and is caught by the
     * media proxy 404ing, not by this sweep.
     */
    @PostMapping("/reconcile")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> reconcile(
            @RequestParam(defaultValue = "media/") String prefix,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "1000") int maxObjects) {
        int cap = Math.max(1, Math.min(maxObjects, 10_000));
        List<S3StorageService.StoredObject> listed;
        try {
            listed = storage.list(prefix, cap);
        } catch (UnsupportedOperationException e) {
            throw new BadRequestException(
                    "Media storage is not configured on this server.", "STORAGE_UNAVAILABLE");
        }

        // Membership in ONE batched IN-query per 1000 keys, not a read per object.
        List<String> orphans = new ArrayList<>();
        long orphanBytes = 0;
        Map<String, Long> sizeByKey = new LinkedHashMap<>();
        List<String> batch = new ArrayList<>(1000);
        for (S3StorageService.StoredObject obj : listed) {
            sizeByKey.put(obj.key(), obj.sizeBytes());
            batch.add(obj.key());
            if (batch.size() == 1000) {
                orphanBytes += collectOrphans(batch, orphans, sizeByKey);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) orphanBytes += collectOrphans(batch, orphans, sizeByKey);

        int deleted = 0;
        if (!dryRun) {
            for (String key : orphans) {
                try {
                    storage.delete(key);
                    deleted++;
                } catch (Exception e) {
                    log.warn("[ADMIN-MEDIA] orphan delete of '{}' failed: {}", key, e.getMessage());
                }
            }
            adminAuditor.record(AuditOperation.DELETE, "MediaStorage", null,
                    "ADMIN_MEDIA_RECONCILE",
                    "prefix=" + prefix + " listed=" + listed.size()
                            + " orphans=" + orphans.size() + " deleted=" + deleted);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefix", prefix);
        body.put("dryRun", dryRun);
        body.put("objectsListed", listed.size());
        body.put("listingTruncated", listed.size() >= cap);
        body.put("orphanCount", orphans.size());
        body.put("orphanBytes", orphanBytes);
        body.put("orphanKeys", orphans.size() > 200 ? orphans.subList(0, 200) : orphans);
        if (!dryRun) body.put("deleted", deleted);
        body.put("note", "An orphan is a bucket object with no media_renditions.object_key row "
                + "and no parseable raw/{assetId}. raw/ objects inside their 7-day retention "
                + "are never flagged.");
        return ResponseEntity.ok(body);
    }

    /** Returns orphan bytes added; fills {@code orphans} from one key batch. */
    private long collectOrphans(List<String> batch, List<String> orphans, Map<String, Long> sizeByKey) {
        java.util.Set<String> known = renditionRepository.findExistingObjectKeys(batch);
        long bytes = 0;
        for (String key : batch) {
            if (known.contains(key)) continue;
            if (key.startsWith("raw/") && rawKeyStillLegit(key)) continue;
            orphans.add(key);
            bytes += sizeByKey.getOrDefault(key, 0L);
        }
        return bytes;
    }

    /** raw/{assetId} keys are legitimate while the asset row exists and its
     *  original hasn't passed the 7-day purge point. */
    private boolean rawKeyStillLegit(String key) {
        try {
            UUID assetId = UUID.fromString(key.substring("raw/".length()));
            return assetRepository.existsById(assetId);
        } catch (Exception e) {
            return false;   // unparseable raw key → orphan
        }
    }

    // ── per-role quotas (media-storage.md §8) ───────────────────────────

    @GetMapping("/quotas")
    public ResponseEntity<List<ak.dev.irc.app.media.entity.MediaQuota>> quotas() {
        return ResponseEntity.ok(quotaRepository.findAll());
    }

    public record QuotaRequest(Integer dailyUploads, Long dailyBytes, Boolean enabled) {}

    @PutMapping("/quotas/{role}")
    @RequiresStepUp
    public ResponseEntity<ak.dev.irc.app.media.entity.MediaQuota> putQuota(
            @PathVariable String role, @RequestBody QuotaRequest req) {
        ak.dev.irc.app.user.enums.Role parsed;
        try {
            parsed = ak.dev.irc.app.user.enums.Role.valueOf(role.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown role. Allowed: "
                    + java.util.Arrays.toString(ak.dev.irc.app.user.enums.Role.values()),
                    "INVALID_ROLE");
        }
        if ((req.dailyUploads() != null && req.dailyUploads() < 1)
                || (req.dailyBytes() != null && req.dailyBytes() < 1)) {
            throw new BadRequestException(
                    "Quota values must be positive — use enabled=false to lift a quota.",
                    "INVALID_QUOTA");
        }
        var quota = quotaRepository.findById(parsed)
                .orElseGet(() -> ak.dev.irc.app.media.entity.MediaQuota.builder()
                        .role(parsed).dailyUploads(100).dailyBytes(2L * 1024 * 1024 * 1024)
                        .build());
        if (req.dailyUploads() != null) quota.setDailyUploads(req.dailyUploads());
        if (req.dailyBytes() != null) quota.setDailyBytes(req.dailyBytes());
        if (req.enabled() != null) quota.setEnabled(req.enabled());
        quota = quotaRepository.save(quota);
        adminAuditor.record(AuditOperation.UPDATE, "MediaQuota", null,
                "ADMIN_MEDIA_QUOTA_CHANGE",
                parsed + " uploads=" + quota.getDailyUploads()
                        + " bytes=" + quota.getDailyBytes() + " enabled=" + quota.isEnabled());
        return ResponseEntity.ok(quota);
    }

    // ── ops snapshot ────────────────────────────────────────────────────

    /** One-call media board: pipeline funnel, backlog, storage, quota config. */
    @GetMapping("/ops")
    public ResponseEntity<Map<String, Object>> ops() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Long> funnel = new LinkedHashMap<>();
        for (MediaStatus s : MediaStatus.values()) funnel.put(s.name(), 0L);
        for (Object[] row : assetRepository.countGroupedByStatus()) {
            funnel.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        body.put("statusFunnel", funnel);
        body.put("abandonedPendingOver24h", assetRepository.findByStatusAndCreatedAtBefore(
                MediaStatus.PENDING, LocalDateTime.now().minusHours(24)).size());
        body.put("rawPurgeDue", assetRepository.findByPurgeOriginalAtBefore(
                LocalDateTime.now()).size());
        body.put("platformStoredBytes", assetRepository.sumStoredBytesPlatform());
        body.put("quotas", quotaRepository.findAll());
        return ResponseEntity.ok(body);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private MediaAsset require(UUID assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("MediaAsset", "id", assetId));
    }

    private static MediaStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MediaStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown status. Allowed: "
                    + java.util.Arrays.toString(MediaStatus.values()), "INVALID_STATUS");
        }
    }

    private static MediaAssetType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MediaAssetType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown type. Allowed: "
                    + java.util.Arrays.toString(MediaAssetType.values()), "INVALID_TYPE");
        }
    }
}
