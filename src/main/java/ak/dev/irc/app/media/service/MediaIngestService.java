package ak.dev.irc.app.media.service;

import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.MediaMessages;
import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.dto.IngestResult;
import ak.dev.irc.app.media.dto.MediaVariants;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaKind;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.MediaSurface;
import ak.dev.irc.app.media.enums.MediaTier;
import ak.dev.irc.app.media.enums.RenditionLabels;
import ak.dev.irc.app.media.event.MediaEventPublisher;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.moderation.image.ImageModerationGate;
import ak.dev.irc.app.moderation.image.ImageScreenResult;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.research.service.VideoMetadataExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single ingest funnel for every multipart upload surface. Validates per
 * {@link MediaSurface} policy, enforces quotas, creates the {@code media_assets}
 * accounting row, and routes: images through the sync {@link ImagePipeline},
 * video to storage + poster + the async transcode queue, audio/files as
 * accounted passthrough.
 *
 * <p><b>Kill switch:</b> {@code media.ingest.enabled=false} reproduces the
 * pre-pipeline behavior exactly (original bytes to the surface's legacy prefix,
 * no rows, no variants) — call sites never branch, they just read the result.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaIngestService {

    private final MediaProperties props;
    private final ImagePipeline imagePipeline;
    private final MediaAssetStatusService statusService;
    private final MediaAssetService assetService;
    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final MediaQuotaService quotaService;
    private final MediaEventPublisher eventPublisher;
    private final MediaDeleteService deleteService;
    private final VideoPosterExtractor posterExtractor;
    private final VideoMetadataExtractor metadataExtractor;
    private final VideoTranscodeService transcoder;
    private final S3StorageService storage;
    private final FileSignatureInspector signatureInspector;
    private final ImageModerationGate imageModerationGate;

    // ── Ingest ───────────────────────────────────────────────────────────────

    /**
     * Ingest one file for {@code surface}.
     *
     * @param legacyPrefix the exact storage prefix this call site used before
     *                     the pipeline — kill-switch mode uploads there verbatim
     */
    public IngestResult ingest(MultipartFile file, MediaSurface surface, UUID ownerId,
                               String legacyPrefix) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(MediaMessages.MEDIA_INVALID_MSG, MediaMessages.MEDIA_INVALID);
        }
        if (!props.getIngest().isEnabled()) {
            return legacyIngest(file, legacyPrefix);
        }

        MediaKind kind = MediaKind.classify(file.getContentType(), file.getOriginalFilename());
        enforceContentSafety(file, kind);
        validateKindAndSize(file, surface, kind);
        if (props.getIngest().isQuotaEnabled() && !surface.quotaExempt() && ownerId != null) {
            quotaService.enforce(ownerId, file.getSize());
        }

        try {
            // NSFW gate (docs/moderation/image-moderation.md): every image —
            // GIF passthrough included — is screened before a byte is stored.
            // A confident block throws MEDIA_NSFW_BLOCKED here; the uncertain
            // review band continues and is filed into the admin queue below,
            // once the asset exists and has a URL to show the reviewer.
            ImageScreenResult screen = null;
            if (kind == MediaKind.IMAGE) {
                screen = imageModerationGate.screen(file.getBytes(), surface, ownerId);
            }

            IngestResult result;
            if (surface.passthroughOnly()) {
                result = ingestPassthrough(file, surface, ownerId, kind);
            } else if (kind == MediaKind.IMAGE
                    && "image/gif".equalsIgnoreCase(file.getContentType())) {
                // Animated GIFs would be flattened to a single JPEG frame — store verbatim.
                result = ingestPassthrough(file, surface, ownerId, kind);
            } else {
                result = switch (kind) {
                    case IMAGE -> ingestImage(file, surface, ownerId);
                    case VIDEO -> ingestVideo(file, surface, ownerId, kind);
                    case AUDIO, FILE -> ingestPassthrough(file, surface, ownerId, kind);
                };
            }
            if (screen != null && screen.needsReview()) {
                imageModerationGate.recordReviewCase(result.assetId(), result.url(),
                        ownerId, surface, screen);
            }
            return result;
        } catch (AppException e) {
            throw e;   // BadRequestException included — both carry a user-facing code
        } catch (Exception e) {
            log.error("[MEDIA-INGEST] {} upload failed on {}: {}", kind, surface, e.getMessage(), e);
            throw new AppException(MediaMessages.MEDIA_INVALID_MSG,
                    HttpStatus.INTERNAL_SERVER_ERROR, MediaMessages.MEDIA_INVALID, e, Map.of());
        }
    }

    /**
     * Ingest several files (posts, chat, research). The count cap is checked
     * before any byte moves; a failure on file N deletes assets 0..N-1 so a
     * half-ingested batch never leaks.
     */
    public List<IngestResult> ingestAll(List<MultipartFile> files, MediaSurface surface,
                                        UUID ownerId, String legacyPrefix) {
        int maxCount = maxCountFor(surface);
        if (files.size() > maxCount) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TOO_MANY_MSG.formatted(maxCount),
                    MediaMessages.MEDIA_TOO_MANY);
        }
        List<IngestResult> results = new ArrayList<>(files.size());
        try {
            for (MultipartFile file : files) {
                results.add(ingest(file, surface, ownerId, legacyPrefix));
            }
            return results;
        } catch (RuntimeException e) {
            for (IngestResult done : results) {
                rollback(done);
            }
            throw e;
        }
    }

    /** Async removal of a pipeline asset (renditions + rows via the delete queue). */
    public void deleteAsset(UUID assetId) {
        if (assetId != null) eventPublisher.publishDeleteAsset(assetId);
    }

    /** Async removal of a pre-pipeline object key. */
    public void deleteLegacyKey(String key) {
        eventPublisher.publishDeleteLegacyKey(key);
    }

    /**
     * Route a stored key to the right delete: deterministic pipeline keys
     * ({@code media/{assetId}/…}) delete the whole asset (all renditions +
     * rows); anything else is a legacy single-object delete. Lets surfaces
     * that only persist a key participate without an assetId column.
     */
    public void deleteByStorageKey(String key) {
        if (key == null || key.isBlank()) return;
        UUID assetId = assetIdFromKey(key);
        if (assetId != null) {
            eventPublisher.publishDeleteAsset(assetId);
        } else {
            eventPublisher.publishDeleteLegacyKey(key);
        }
    }

    /** Parse the assetId out of a {@code media/{assetId}/…} key; null otherwise. */
    public static UUID assetIdFromKey(String key) {
        if (key == null || !key.startsWith("media/")) return null;
        int slash = key.indexOf('/', 6);
        if (slash < 0) return null;
        try {
            return UUID.fromString(key.substring(6, slash));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Delete whatever an IngestResult stored — asset or legacy key. Synchronous. */
    public void rollback(IngestResult result) {
        try {
            if (result.assetId() != null) {
                deleteService.deleteAssetNow(result.assetId());
            } else if (result.storageKey() != null) {
                deleteService.deleteLegacyKey(result.storageKey());
            }
        } catch (Exception ex) {
            log.warn("[MEDIA-INGEST] rollback failed for {}: {}", result.storageKey(), ex.getMessage());
        }
    }

    // ── Branches ─────────────────────────────────────────────────────────────

    private IngestResult ingestImage(MultipartFile file, MediaSurface surface, UUID ownerId)
            throws Exception {
        byte[] bytes = file.getBytes();
        String hash = sha256Hex(bytes);
        ImagePlan plan = planFor(surface);
        if (plan == null) {
            return ingestPassthrough(file, surface, ownerId, MediaKind.IMAGE);
        }
        MediaAsset asset = statusService.createAsset(ownerId, MediaAssetType.IMAGE,
                MediaStatus.PROCESSING, hash, file.getSize(), file.getContentType(), MediaTier.HIGH);
        try {
            ImagePipeline.ImageOutcome outcome =
                    imagePipeline.processAndPersist(asset.getId(), bytes, plan);
            return buildResult(asset.getId(), MediaKind.IMAGE, outcome.renditions(),
                    outcome.width(), outcome.height(), null,
                    file.getSize(), file.getContentType(), file.getOriginalFilename(), false);
        } catch (IllegalArgumentException bad) {
            deleteService.deleteAssetNow(asset.getId());
            throw new BadRequestException(bad.getMessage() != null
                    ? bad.getMessage() : MediaMessages.MEDIA_INVALID_MSG,
                    MediaMessages.MEDIA_INVALID);
        } catch (IllegalStateException busy) {
            deleteService.deleteAssetNow(asset.getId());
            throw new AppException(MediaMessages.MEDIA_BUSY_MSG,
                    HttpStatus.SERVICE_UNAVAILABLE, MediaMessages.MEDIA_BUSY);
        } catch (Exception e) {
            deleteService.deleteAssetNow(asset.getId());
            throw e;
        }
    }

    private IngestResult ingestVideo(MultipartFile file, MediaSurface surface, UUID ownerId,
                                     MediaKind kind) throws Exception {
        MediaAssetType type = surface.assetTypeFor(kind);
        Path tmp = Files.createTempFile("ingest-", ".bin");
        try {
            String hash = copyAndHash(file, tmp);

            // Duration cap — best-effort at intake (mp4/mov; webm can't be probed
            // here). The worker skips the ladder for over-long interception video
            // instead of failing the already-published asset.
            Integer durationSeconds = metadataExtractor.extractDurationSeconds(tmp);
            Integer cap = durationCapFor(surface, type);
            if (durationSeconds != null && cap != null && durationSeconds > cap) {
                throw new BadRequestException(
                        MediaMessages.MEDIA_DURATION_EXCEEDED_MSG.formatted(cap),
                        MediaMessages.MEDIA_DURATION_EXCEEDED);
            }

            // Dedup: identical bytes reuse the READY ladder — no re-transcode.
            IngestResult dedup = tryDedup(ownerId, hash, kind, file);
            if (dedup != null) return dedup;

            MediaAsset asset = statusService.createAsset(ownerId, type, MediaStatus.PROCESSING,
                    hash, file.getSize(), file.getContentType(), MediaTier.HIGH);
            UUID assetId = asset.getId();
            try {
                long stored = storeOriginal(assetId, tmp, file);

                // Poster-frame NSFW screen: a video whose representative frame is
                // confidently explicit is rejected here (the catch below removes
                // the stored original); the review band publishes and is queued.
                byte[] poster = extractPoster(assetId, tmp);
                ImageScreenResult posterScreen = null;
                if (poster != null && imageModerationGate.videoPostersEnabled()) {
                    posterScreen = imageModerationGate.screen(poster, surface, ownerId);
                }
                stored += attachPoster(assetId, poster);

                VideoTranscodeService.SourceInfo info = transcoder.probe(tmp);
                Integer durationMs = durationSeconds != null ? durationSeconds * 1000
                        : (info == null ? null : info.durationMs());

                boolean async = props.getIngest().isVideoAsyncEnabled();
                if (async) {
                    eventPublisher.publishProcessRequested(assetId, "ingest");
                } else {
                    statusService.markReady(assetId, stored,
                            info == null ? null : info.width(),
                            info == null ? null : info.height(),
                            durationMs, null);
                }
                IngestResult result = buildResult(assetId, kind,
                        renditionRepo.findByIdMediaId(assetId),
                        info == null ? null : info.width(),
                        info == null ? null : info.height(),
                        durationMs == null ? null : durationMs / 1000,
                        file.getSize(), file.getContentType(), file.getOriginalFilename(), async);
                if (posterScreen != null && posterScreen.needsReview()) {
                    imageModerationGate.recordReviewCase(assetId,
                            storage.getPublicUrl("media/" + assetId + "/poster.jpg"),
                            ownerId, surface, posterScreen);
                }
                return result;
            } catch (Exception e) {
                deleteService.deleteAssetNow(assetId);
                throw e;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private IngestResult ingestPassthrough(MultipartFile file, MediaSurface surface, UUID ownerId,
                                           MediaKind kind) throws Exception {
        MediaAssetType type = surface.assetTypeFor(kind);
        Path tmp = Files.createTempFile("ingest-", ".bin");
        try {
            String hash = copyAndHash(file, tmp);
            IngestResult dedup = tryDedup(ownerId, hash, kind, file);
            if (dedup != null) return dedup;

            MediaAsset asset = statusService.createAsset(ownerId, type, MediaStatus.PROCESSING,
                    hash, file.getSize(), file.getContentType(), MediaTier.HIGH);
            UUID assetId = asset.getId();
            try {
                long stored = storeOriginal(assetId, tmp, file);
                Integer durationSeconds = kind == MediaKind.AUDIO
                        ? metadataExtractor.extractDurationSeconds(tmp) : null;
                statusService.markReady(assetId, stored, null, null,
                        durationSeconds == null ? null : durationSeconds * 1000, null);
                return buildResult(assetId, kind, renditionRepo.findByIdMediaId(assetId),
                        null, null, durationSeconds,
                        file.getSize(), file.getContentType(), file.getOriginalFilename(), false);
            } catch (Exception e) {
                deleteService.deleteAssetNow(assetId);
                throw e;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private IngestResult legacyIngest(MultipartFile file, String legacyPrefix) {
        MediaKind kind = MediaKind.classify(file.getContentType(), file.getOriginalFilename());
        String key = storage.upload(file, legacyPrefix);
        return new IngestResult(null, kind, storage.getPublicUrl(key), null, key, Map.of(),
                null, null, null, file.getSize(), file.getContentType(),
                file.getOriginalFilename(), false);
    }

    // ── Pieces ───────────────────────────────────────────────────────────────

    private long storeOriginal(UUID assetId, Path tmp, MultipartFile file) throws Exception {
        String key = "media/" + assetId + "/original" + extensionOf(file.getOriginalFilename());
        storage.putFile(tmp, key, file.getContentType());
        long size = Files.size(tmp);
        statusService.saveRendition(MediaRendition.builder()
                .id(new MediaRendition.MediaRenditionId(assetId, RenditionLabels.ORIGINAL))
                .objectKey(key)
                .url(storage.getPublicUrl(key))
                .bytes(size)
                .mime(file.getContentType())
                .build());
        return size;
    }

    /** Best-effort poster frame; null when the video can't be probed. */
    private byte[] extractPoster(UUID assetId, Path video) {
        try {
            return posterExtractor.extract(video);
        } catch (Exception ex) {
            log.warn("[MEDIA-INGEST] poster extraction for {} failed: {}", assetId, ex.getMessage());
            return null;
        }
    }

    private long attachPoster(UUID assetId, byte[] poster) {
        try {
            if (poster == null) return 0;
            String key = "media/" + assetId + "/poster.jpg";
            storage.putBytes(poster, key, "image/jpeg");
            statusService.saveRendition(MediaRendition.builder()
                    .id(new MediaRendition.MediaRenditionId(assetId, RenditionLabels.POSTER))
                    .objectKey(key)
                    .url(storage.getPublicUrl(key))
                    .bytes((long) poster.length)
                    .mime("image/jpeg")
                    .build());
            return poster.length;
        } catch (Exception ex) {
            log.warn("[MEDIA-INGEST] poster for {} failed: {}", assetId, ex.getMessage());
            return 0;
        }
    }

    /**
     * Byte-identical re-upload of a READY asset → a reference row sharing its
     * renditions. Images are excluded: plans differ per surface, and an image
     * re-encode is cheap anyway.
     */
    private IngestResult tryDedup(UUID ownerId, String hash, MediaKind kind, MultipartFile file) {
        if (hash == null || kind == MediaKind.IMAGE) return null;
        return assetRepo.findFirstByContentHashAndStatus(hash, MediaStatus.READY)
                .map(src -> {
                    MediaAsset ref = assetService.dedupReference(ownerId, src, MediaTier.HIGH);
                    List<MediaRendition> renditions = renditionRepo.findByIdMediaId(ref.getId());
                    log.info("[MEDIA-INGEST] dedup hit — {} reuses {}", ref.getId(), src.getId());
                    return buildResult(ref.getId(), kind, renditions,
                            src.getWidth(), src.getHeight(),
                            src.getDurationMs() == null ? null : src.getDurationMs() / 1000,
                            file.getSize(), file.getContentType(), file.getOriginalFilename(), false);
                })
                .orElse(null);
    }

    private IngestResult buildResult(UUID assetId, MediaKind kind, List<MediaRendition> renditions,
                                     Integer width, Integer height, Integer durationSeconds,
                                     long bytes, String mime, String fileName, boolean processing) {
        Map<String, String> variants = MediaVariants.toClientMap(renditions);
        String url;
        if (kind == MediaKind.IMAGE) {
            url = MediaVariants.primaryUrl(variants);
        } else if (kind == MediaKind.VIDEO && !processing) {
            url = MediaVariants.bestVideoUrl(variants);
        } else {
            url = variants.getOrDefault("original", MediaVariants.primaryUrl(variants));
        }
        String storageKey = null;
        for (MediaRendition r : renditions) {
            if (r.getUrl() != null && r.getUrl().equals(url)) {
                storageKey = r.getObjectKey();
                break;
            }
        }
        return new IngestResult(assetId, kind, url, MediaVariants.thumbnailUrl(variants),
                storageKey, variants, width, height, durationSeconds, bytes, mime, fileName,
                processing);
    }

    // ── Validation / policy resolution ───────────────────────────────────────

    /**
     * Server-side content verification — the declared MIME type and the filename
     * extension are both client-controlled, so the leading bytes are the only
     * trustworthy signal. Two gates:
     * <ol>
     *   <li><b>Executable veto</b> (default ON): native/script executable content
     *       — detected by magic bytes (PE/ELF/Mach-O/Java class/shebang) or by the
     *       {@code media.security.blocked-extensions} list — is rejected on every
     *       surface unless {@code media.security.allow-executables=true}.</li>
     *   <li><b>Spoof check</b>: a file whose sniffed family contradicts its
     *       classified kind (an archive posing as a JPEG, a PDF posing as a video)
     *       is rejected. Deliberately lenient — UNKNOWN sniffs pass (the decoders
     *       are the final arbiter for processed media), and the known container
     *       quirks (m4a declared {@code video/mp4}, audio-only MP4) stay legal.</li>
     * </ol>
     */
    private void enforceContentSafety(MultipartFile file, MediaKind kind) {
        MediaProperties.Security sec = props.getSecurity();
        if (!sec.isMagicCheckEnabled()) return;

        if (!sec.isAllowExecutables()) {
            String ext = bareExtensionOf(file.getOriginalFilename());
            if (ext != null && sec.getBlockedExtensions().contains(ext)) {
                throw new BadRequestException(
                        MediaMessages.MEDIA_TYPE_BLOCKED_MSG.formatted("." + ext),
                        MediaMessages.MEDIA_TYPE_BLOCKED);
            }
        }

        FileSignatureInspector.Sniff sniff;
        try (var in = file.getInputStream()) {
            sniff = signatureInspector.inspect(signatureInspector.readHeader(in));
        } catch (IOException e) {
            throw new BadRequestException(MediaMessages.MEDIA_INVALID_MSG, MediaMessages.MEDIA_INVALID);
        }

        if (sniff.executable() && !sec.isAllowExecutables()) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TYPE_BLOCKED_MSG.formatted(sniff.detected()),
                    MediaMessages.MEDIA_TYPE_BLOCKED);
        }
        if (contradicts(kind, sniff.family())) {
            log.warn("[MEDIA-INGEST] content mismatch: declared {} ({}) but sniffed {} ({})",
                    kind, file.getContentType(), sniff.family(), sniff.detected());
            throw new BadRequestException(
                    MediaMessages.MEDIA_CONTENT_MISMATCH_MSG, MediaMessages.MEDIA_CONTENT_MISMATCH);
        }
    }

    /** True when a *known* sniffed family is incompatible with the classified kind. */
    private static boolean contradicts(MediaKind kind, FileSignatureInspector.Family family) {
        return switch (kind) {
            case IMAGE -> family == FileSignatureInspector.Family.VIDEO
                    || family == FileSignatureInspector.Family.AUDIO
                    || family == FileSignatureInspector.Family.PDF
                    || family == FileSignatureInspector.Family.ARCHIVE;
            // AUDIO family stays legal for VIDEO kind (m4a is declared video/mp4 by
            // browsers) and VIDEO for AUDIO kind (audio-only .mp4).
            case VIDEO, AUDIO -> family == FileSignatureInspector.Family.IMAGE
                    || family == FileSignatureInspector.Family.PDF
                    || family == FileSignatureInspector.Family.ARCHIVE;
            case FILE -> false;   // generic files: anything non-executable is fine
        };
    }

    /** Lower-case extension without the dot (blocklist key); null when absent. */
    private static String bareExtensionOf(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Fail-fast policy check for the chunked-upload <i>init</i> step — rejects a
     * doomed upload before any byte moves (extension blocklist, kind allowlist,
     * per-surface byte cap). {@code ingest()} re-applies everything
     * authoritatively at complete time, including the magic-byte gate that
     * needs the actual bytes.
     */
    public void assertUploadAllowed(MediaSurface surface, String fileName, String mime, long totalBytes) {
        MediaKind kind = MediaKind.classify(mime, fileName);
        MediaProperties.Security sec = props.getSecurity();
        if (sec.isMagicCheckEnabled() && !sec.isAllowExecutables()) {
            String ext = bareExtensionOf(fileName);
            if (ext != null && sec.getBlockedExtensions().contains(ext)) {
                throw new BadRequestException(
                        MediaMessages.MEDIA_TYPE_BLOCKED_MSG.formatted("." + ext),
                        MediaMessages.MEDIA_TYPE_BLOCKED);
            }
        }
        if (!surface.allowedKinds().contains(kind)) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TYPE_NOT_ALLOWED_MSG.formatted(mime == null ? kind.name() : mime),
                    MediaMessages.MEDIA_TYPE_NOT_ALLOWED);
        }
        long cap = byteCapFor(surface, kind);
        if (totalBytes > cap) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TOO_LARGE_MSG.formatted(kind, cap),
                    MediaMessages.MEDIA_TOO_LARGE);
        }
    }

    private void validateKindAndSize(MultipartFile file, MediaSurface surface, MediaKind kind) {
        if (!surface.allowedKinds().contains(kind)) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TYPE_NOT_ALLOWED_MSG.formatted(
                            file.getContentType() == null ? kind.name() : file.getContentType()),
                    MediaMessages.MEDIA_TYPE_NOT_ALLOWED);
        }
        long cap = byteCapFor(surface, kind);
        if (file.getSize() > cap) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TOO_LARGE_MSG.formatted(kind, cap),
                    MediaMessages.MEDIA_TOO_LARGE);
        }
    }

    private MediaProperties.SurfaceOverride overrideFor(MediaSurface surface) {
        return props.getSurfaces().get(surface.configKey());
    }

    private long byteCapFor(MediaSurface surface, MediaKind kind) {
        MediaProperties.SurfaceOverride o = overrideFor(surface);
        Long override = o == null ? null : switch (kind) {
            case IMAGE -> o.getImageMaxBytes();
            case VIDEO -> o.getVideoMaxBytes();
            case AUDIO -> o.getAudioMaxBytes();
            case FILE -> o.getFileMaxBytes();
        };
        if (override != null) return override;
        if (kind == MediaKind.IMAGE && surface.imageMaxBytesDefault() != null) {
            return surface.imageMaxBytesDefault();
        }
        return switch (kind) {
            case IMAGE -> props.getLimits().getImageMaxBytes();
            case VIDEO -> props.getLimits().getVideoMaxBytes();
            case AUDIO -> props.getLimits().getAudioMaxBytes();
            case FILE -> props.getLimits().getFileMaxBytes();
        };
    }

    private int maxCountFor(MediaSurface surface) {
        MediaProperties.SurfaceOverride o = overrideFor(surface);
        return o != null && o.getMaxCount() != null ? o.getMaxCount() : surface.maxCount();
    }

    private Integer durationCapFor(MediaSurface surface, MediaAssetType type) {
        MediaProperties.SurfaceOverride o = overrideFor(surface);
        if (o != null && o.getMaxDurationSeconds() != null) return o.getMaxDurationSeconds();
        if (surface.maxDurationSeconds() != null) return surface.maxDurationSeconds();
        MediaProperties.Video.DurationCaps caps = props.getVideo().getDurationCaps();
        return switch (type) {
            case VIDEO_CLIP -> caps.getVideoClipSeconds();
            case FILM -> caps.getFilmSeconds();
            default -> caps.getVideoSeconds();
        };
    }

    private ImagePlan planFor(MediaSurface surface) {
        return switch (surface.planKind()) {
            case FULL -> ImagePlan.full(props);
            case FEED -> ImagePlan.feed(props);
            case CHAT -> ImagePlan.chat(props);
            case PROFILE -> ImagePlan.profile(props);
            case NONE -> null;
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /** Spool the multipart to {@code target}, hashing on the way — one pass. */
    private static String copyAndHash(MultipartFile file, Path target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(file.getInputStream(), digest);
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String extensionOf(String filename) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1 && filename.length() - dot <= 6) {
                return filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return ".bin";
    }
}
