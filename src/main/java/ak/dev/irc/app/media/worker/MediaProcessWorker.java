package ak.dev.irc.app.media.worker;

import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.MediaTier;
import ak.dev.irc.app.media.enums.RenditionLabels;
import ak.dev.irc.app.media.event.MediaProcessRequestedEvent;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.media.service.BlurHashEncoder;
import ak.dev.irc.app.media.service.FfmpegRunner;
import ak.dev.irc.app.media.service.HlsPackagingService;
import ak.dev.irc.app.media.service.ImagePipeline;
import ak.dev.irc.app.media.service.ImagePlan;
import ak.dev.irc.app.media.service.MediaAssetStatusService;
import ak.dev.irc.app.media.service.MediaReadyDispatcher;
import ak.dev.irc.app.media.service.MediaScanner;
import ak.dev.irc.app.media.service.VideoPosterExtractor;
import ak.dev.irc.app.media.service.VideoPreviewExtractor;
import ak.dev.irc.app.media.service.VideoTranscodeService;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.MEDIA_PROCESS_QUEUE;

/**
 * The media transcode worker — consumes {@code irc.queue.media.process}
 * (declared with DLX + 24h TTL; the dedicated container factory retries 3×
 * then dead-letters into the parking lot).
 *
 * <p>Error discipline mirrors the moderation worker: input that can never
 * succeed (undecodable, over the duration cap) is persisted as a terminal
 * failure and <b>swallowed</b>; transient trouble (storage down, ffmpeg
 * timeout) records an attempt and <b>rethrows</b> so the container backs off.
 * The asset row stays PROCESSING through retries — {@code MediaStuckSweeper}
 * owns the final outcome if every path loses the message.</p>
 *
 * <p>Renditions are produced ascending (360p first) and persisted one by one,
 * so a mid-ladder crash resumes instead of restarting and a playable rendition
 * exists as early as possible.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaProcessWorker {

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final MediaAssetStatusService statusService;
    private final S3StorageService storage;
    private final ImagePipeline imagePipeline;
    private final VideoTranscodeService transcoder;
    private final VideoPosterExtractor posterExtractor;
    private final VideoPreviewExtractor previewExtractor;
    private final HlsPackagingService hlsPackager;
    private final FfmpegRunner ffmpeg;
    private final MediaScanner scanner;
    private final MediaProperties props;
    private final MediaReadyDispatcher readyDispatcher;

    @RabbitListener(queues = MEDIA_PROCESS_QUEUE, containerFactory = "mediaListenerContainerFactory")
    public void onProcessRequested(MediaProcessRequestedEvent event) {
        UUID assetId = event.getAssetId();
        if (assetId == null) return;
        MediaAsset asset = assetRepo.findById(assetId).orElse(null);
        if (asset == null) {
            log.warn("[MEDIA-WORKER] asset {} not found — dropping message", assetId);
            return;
        }
        if (asset.getStatus() == MediaStatus.READY
                || asset.getStatus() == MediaStatus.FAILED_VALIDATION
                || asset.getStatus() == MediaStatus.FAILED_MODERATION
                || asset.getStatus() == MediaStatus.FAILED_PROCESSING) {
            return; // idempotent redelivery
        }
        if (asset.getStatus() != MediaStatus.PROCESSING) {
            statusService.markProcessing(assetId);
        }

        Path source = null;
        try {
            SourceRef ref = resolveSource(assetId);
            if (ref == null) {
                statusService.markFailed(assetId, MediaStatus.FAILED_VALIDATION,
                        "Source object not found in storage.");
                return;
            }
            source = downloadToTemp(ref.key());

            if (asset.getType() == MediaAssetType.IMAGE) {
                processImage(asset, source, ref);
            } else if (asset.getType().isVideo()) {
                processVideo(asset, source, ref);
            } else {
                processPassthrough(asset, source, ref);
            }
        } catch (IllegalArgumentException bad) {
            // Bad input — retrying can't fix it. Terminal, swallowed.
            statusService.markFailed(assetId, MediaStatus.FAILED_VALIDATION, bad.getMessage());
        } catch (Exception transientEx) {
            statusService.recordAttemptFailure(assetId, transientEx.getMessage());
            log.warn("[MEDIA-WORKER] transient failure for {} (attempt recorded): {}",
                    assetId, transientEx.getMessage());
            throw transientEx instanceof RuntimeException re
                    ? re : new IllegalStateException(transientEx);
        } finally {
            deleteQuietly(source);
        }
    }

    // ── Branches ─────────────────────────────────────────────────────────────

    private void processImage(MediaAsset asset, Path source, SourceRef ref) throws Exception {
        byte[] bytes = Files.readAllBytes(source);   // images are ≤ 25 MB by intake cap
        if (!scanner.isClean(bytes, asset.getMime())) {
            statusService.markFailed(asset.getId(), MediaStatus.FAILED_MODERATION,
                    "Rejected by moderation scan.");
            return;
        }
        MediaTier tier = asset.getRequestedTier() == null ? MediaTier.HIGH : asset.getRequestedTier();
        imagePipeline.processAndPersist(asset.getId(), bytes,
                ImagePlan.full(props).clampTo(tier.imageLongEdge()));
        if (ref.raw()) {
            statusService.setRawRetention(asset.getId(), LocalDateTime.now().plusDays(7));
        }
        readyDispatcher.dispatch(asset.getId());
    }

    private void processVideo(MediaAsset asset, Path source, SourceRef ref) throws Exception {
        UUID assetId = asset.getId();
        // SEAM: a real scanner needs a file-based API; the current one is a no-op.
        if (!scanner.isClean(new byte[0], asset.getMime())) {
            statusService.markFailed(assetId, MediaStatus.FAILED_MODERATION,
                    "Rejected by moderation scan.");
            return;
        }

        VideoTranscodeService.SourceInfo info = transcoder.probe(source);
        boolean overDuration = false;
        if (info != null && info.durationMs() != null) {
            int capSeconds = durationCapSeconds(asset.getType());
            if (info.durationMs() > capSeconds * 1000L) {
                if (ref.raw()) {
                    // Intent flow: nothing published yet — reject outright.
                    throw new IllegalArgumentException("Video exceeds the %ds duration limit for %s."
                            .formatted(capSeconds, asset.getType()));
                }
                // Interception: the original is already published (the ingest-time
                // cap is best-effort for non-MP4 containers) — keep it servable
                // but don't burn CPU on a ladder for it.
                overDuration = true;
                log.warn("[MEDIA-WORKER] {} exceeds the duration cap — ladder skipped", assetId);
            }
        }

        ensureOriginalRendition(asset, source, ref);
        boolean transcode = props.getProcessing().isEnabled() && ffmpeg.ffmpegAvailable()
                && !overDuration;

        Integer topW = info == null ? null : info.width();
        Integer topH = info == null ? null : info.height();

        if (transcode && info == null) {
            if (ref.raw()) {
                throw new IllegalArgumentException("Video stream is unreadable (ffprobe found no video).");
            }
            // Interception asset that ffprobe can't read (or ffprobe is absent):
            // degrade to passthrough rather than failing a published upload.
            transcode = false;
            log.warn("[MEDIA-WORKER] {} not probeable — ladder skipped, passthrough kept", assetId);
        }
        // Local rung files this run produced (or re-downloaded), so the HLS
        // packager can remux without re-encoding. Cleaned in the finally.
        java.util.Map<String, Path> localRungs = new java.util.HashMap<>();
        try {
            if (transcode) {
                MediaTier tier = asset.getRequestedTier() == null ? MediaTier.HIGH : asset.getRequestedTier();
                int hardCap = Math.min(tier.videoShortEdge(), props.getVideo().getMaxShortEdge());
                int sourceShortEdge = info.shortEdge();

                List<MediaProperties.Video.Rung> ladder = props.getVideo().effectiveLadder();
                for (int i = 0; i < ladder.size(); i++) {                // ascending
                    MediaProperties.Video.Rung rung = ladder.get(i);
                    // Never upscale and never exceed the tier/platform cap — except
                    // the lowest rung, which always encodes (at the clamped size) so
                    // at least one H.264/+faststart rendition exists.
                    boolean lowest = i == 0;
                    if (!lowest && (rung.getShortEdge() > hardCap
                            || rung.getShortEdge() > sourceShortEdge)) continue;
                    if (statusService.renditionExists(assetId, rung.getLabel())) continue; // retry resume
                    int encodeCap = Math.min(Math.min(rung.getShortEdge(), hardCap), sourceShortEdge);
                    Path rungFile = transcodeRung(assetId, source, rung, encodeCap);
                    localRungs.put(rung.getLabel(), rungFile);
                }
                // Dims of the best produced rung, for the asset row.
                long bestArea = -1;
                for (MediaRendition r : renditionRepo.findByIdMediaId(assetId)) {
                    if (!isLadderLabel(r.getId().getLabel())
                            || r.getWidth() == null || r.getHeight() == null) continue;
                    long area = (long) r.getWidth() * r.getHeight();
                    if (area > bestArea) {
                        bestArea = area;
                        topW = r.getWidth();
                        topH = r.getHeight();
                    }
                }
            }

            maybePackageHls(asset, localRungs, info, topW, topH);
        } finally {
            localRungs.values().forEach(MediaProcessWorker::deleteQuietly);
        }

        ensurePoster(asset, source);
        ensurePreview(asset, source);

        long stored = renditionRepo.findByIdMediaId(assetId).stream()
                .mapToLong(r -> r.getBytes() == null ? 0 : r.getBytes()).sum();
        statusService.markReady(assetId, stored, topW, topH,
                info == null ? null : info.durationMs(),
                ref.raw() ? LocalDateTime.now().plusDays(7) : null);
        log.info("[MEDIA-WORKER] video asset {} READY (transcode={})", assetId, transcode);
        readyDispatcher.dispatch(assetId);
    }

    private void processPassthrough(MediaAsset asset, Path source, SourceRef ref) throws Exception {
        ensureOriginalRendition(asset, source, ref);
        long stored = renditionRepo.findByIdMediaId(asset.getId()).stream()
                .mapToLong(r -> r.getBytes() == null ? 0 : r.getBytes()).sum();
        statusService.markReady(asset.getId(), stored, null, null, null,
                ref.raw() ? LocalDateTime.now().plusDays(7) : null);
        readyDispatcher.dispatch(asset.getId());
    }

    // ── Pieces ───────────────────────────────────────────────────────────────

    /**
     * Encode + upload one rung. Returns the LOCAL output file — the caller
     * keeps it alive for the HLS remux and owns its deletion.
     */
    private Path transcodeRung(UUID assetId, Path source,
                               MediaProperties.Video.Rung rung, int shortEdgeCap) throws Exception {
        Path out = Files.createTempFile("media-rung-", ".mp4");
        try {
            if (!transcoder.transcodeRung(source, out, rung, shortEdgeCap)) {
                throw new IllegalStateException("ffmpeg failed on rung " + rung.getLabel());
            }
            String key = "media/" + assetId + "/" + rung.getLabel() + ".mp4";
            storage.putFile(out, key, "video/mp4");
            VideoTranscodeService.SourceInfo outInfo = transcoder.probe(out);
            statusService.saveRendition(MediaRendition.builder()
                    .id(new MediaRendition.MediaRenditionId(assetId, rung.getLabel()))
                    .objectKey(key)
                    .url(storage.getPublicUrl(key))
                    .bytes(Files.size(out))
                    .width(outInfo == null ? null : outInfo.width())
                    .height(outInfo == null ? null : outInfo.height())
                    .mime("video/mp4")
                    .build());
            log.info("[MEDIA-WORKER] {} rung {} done ({} bytes)", assetId, rung.getLabel(), Files.size(out));
            return out;
        } catch (Exception ex) {
            deleteQuietly(out);
            throw ex;
        }
    }

    /**
     * CMAF/fMP4 HLS packaging of whatever ladder exists. Rungs encoded this
     * run are remuxed from their local files; on a retry-resume the missing
     * ones are re-downloaded. A packaging (ffmpeg) failure is soft — the MP4
     * ladder still serves; a storage failure propagates as transient so the
     * retry re-runs packaging idempotently.
     */
    private void maybePackageHls(MediaAsset asset, java.util.Map<String, Path> localRungs,
                                 VideoTranscodeService.SourceInfo info,
                                 Integer topW, Integer topH) throws Exception {
        UUID assetId = asset.getId();
        if (!props.getVideo().getHls().isEnabled() || !ffmpeg.ffmpegAvailable()) return;
        if (statusService.renditionExists(assetId, RenditionLabels.HLS)) return;

        List<MediaRendition> ladderRenditions = renditionRepo.findByIdMediaId(assetId).stream()
                .filter(r -> isLadderLabel(r.getId().getLabel()))
                .toList();
        if (ladderRenditions.isEmpty()) return;   // passthrough asset — nothing to package

        java.util.Map<String, MediaProperties.Video.Rung> rungCfg = new java.util.HashMap<>();
        for (MediaProperties.Video.Rung r : props.getVideo().effectiveLadder()) {
            rungCfg.put(r.getLabel(), r);
        }

        List<Path> downloaded = new java.util.ArrayList<>();
        try {
            List<HlsPackagingService.RungInput> inputs = new java.util.ArrayList<>();
            for (MediaRendition r : ladderRenditions) {
                String label = r.getId().getLabel();
                Path local = localRungs.get(label);
                if (local == null || !Files.exists(local)) {
                    local = downloadToTemp(r.getObjectKey());   // resume path
                    downloaded.add(local);
                }
                MediaProperties.Video.Rung cfg = rungCfg.get(label);
                inputs.add(new HlsPackagingService.RungInput(
                        label, local, r.getWidth(), r.getHeight(), r.getBytes(),
                        cfg == null ? null : cfg.getMaxrate(),
                        cfg == null ? null : cfg.getAudioBitrate()));
            }
            Integer durationMs = info != null && info.durationMs() != null
                    ? info.durationMs() : asset.getDurationMs();
            HlsPackagingService.HlsResult result = hlsPackager.packageLadder(assetId, inputs, durationMs);
            if (result == null) return;   // soft — logged by the packager

            statusService.saveRendition(MediaRendition.builder()
                    .id(new MediaRendition.MediaRenditionId(assetId, RenditionLabels.HLS))
                    .objectKey(result.masterKey())
                    .url(storage.getPublicUrl(result.masterKey()))
                    .bytes(result.totalBytes())
                    .width(topW)
                    .height(topH)
                    .mime(HlsPackagingService.MASTER_MIME)
                    .build());
            log.info("[MEDIA-WORKER] {} HLS master ready ({} variants)", assetId, result.variantCount());
        } finally {
            downloaded.forEach(MediaProcessWorker::deleteQuietly);
        }
    }

    /**
     * Interception-path assets already store their original as a rendition;
     * intent-flow assets have only {@code raw/} — copy it to a public,
     * deterministic key so passthrough mode still yields a servable object.
     */
    private void ensureOriginalRendition(MediaAsset asset, Path source, SourceRef ref) throws Exception {
        if (statusService.renditionExists(asset.getId(), RenditionLabels.ORIGINAL)) return;
        String key = "media/" + asset.getId() + "/original" + extensionFor(asset.getMime());
        storage.putFile(source, key, asset.getMime());
        statusService.saveRendition(MediaRendition.builder()
                .id(new MediaRendition.MediaRenditionId(asset.getId(), RenditionLabels.ORIGINAL))
                .objectKey(key)
                .url(storage.getPublicUrl(key))
                .bytes(Files.size(source))
                .mime(asset.getMime())
                .build());
    }

    private void ensurePoster(MediaAsset asset, Path source) {
        try {
            if (statusService.renditionExists(asset.getId(), RenditionLabels.POSTER)) return;
            byte[] poster = posterExtractor.extract(source);
            if (poster == null) return;   // soft-fail, as always
            String key = "media/" + asset.getId() + "/poster.jpg";
            storage.putBytes(poster, key, "image/jpeg");
            statusService.saveRendition(MediaRendition.builder()
                    .id(new MediaRendition.MediaRenditionId(asset.getId(), RenditionLabels.POSTER))
                    .objectKey(key)
                    .url(storage.getPublicUrl(key))
                    .bytes((long) poster.length)
                    .mime("image/jpeg")
                    .build());
            // Placeholder hash from the poster frame — clients paint it before
            // any real bytes arrive. Encoder returns null on failure; soft.
            statusService.setBlurhash(asset.getId(), BlurHashEncoder.encode(poster));
        } catch (Exception ex) {
            log.warn("[MEDIA-WORKER] poster for {} failed: {}", asset.getId(), ex.getMessage());
        }
    }

    /** Animated WebP hover preview — same soft-fail contract as the poster. */
    private void ensurePreview(MediaAsset asset, Path source) {
        try {
            if (statusService.renditionExists(asset.getId(), RenditionLabels.PREVIEW)) return;
            byte[] preview = previewExtractor.extract(source);
            if (preview == null) return;
            String key = "media/" + asset.getId() + "/preview.webp";
            storage.putBytes(preview, key, "image/webp");
            statusService.saveRendition(MediaRendition.builder()
                    .id(new MediaRendition.MediaRenditionId(asset.getId(), RenditionLabels.PREVIEW))
                    .objectKey(key)
                    .url(storage.getPublicUrl(key))
                    .bytes((long) preview.length)
                    .mime("image/webp")
                    .build());
        } catch (Exception ex) {
            log.warn("[MEDIA-WORKER] preview for {} failed: {}", asset.getId(), ex.getMessage());
        }
    }

    /** Where the source bytes live: {@code raw} is true for the intent flow's raw/ key. */
    private record SourceRef(String key, boolean raw) {}

    /** Resolve the source: the original rendition (interception) or raw/ (intent). */
    private SourceRef resolveSource(UUID assetId) {
        return renditionRepo.findByIdMediaId(assetId).stream()
                .filter(r -> RenditionLabels.ORIGINAL.equals(r.getId().getLabel()))
                .findFirst()
                .map(r -> new SourceRef(r.getObjectKey(), false))
                .orElseGet(() -> {
                    String rawKey = "raw/" + assetId;
                    try {
                        // Cheap existence probe: a 1-byte ranged read.
                        storage.getObject(rawKey, "bytes=0-0").inputStream().close();
                        return new SourceRef(rawKey, true);
                    } catch (Exception ex) {
                        return null;
                    }
                });
    }

    private Path downloadToTemp(String key) throws Exception {
        Path tmp = Files.createTempFile("media-src-", ".bin");
        try (InputStream in = storage.getObject(key).inputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            // The path never reaches a caller's cleanup on failure — delete
            // here or repeated storage flakiness strands multi-hundred-MB temps.
            deleteQuietly(tmp);
            throw ex;
        }
        return tmp;
    }

    private int durationCapSeconds(MediaAssetType type) {
        MediaProperties.Video.DurationCaps caps = props.getVideo().getDurationCaps();
        return switch (type) {
            case VIDEO_CLIP -> caps.getVideoClipSeconds();
            case FILM -> caps.getFilmSeconds();
            default -> caps.getVideoSeconds();
        };
    }

    private static boolean isLadderLabel(String label) {
        return RenditionLabels.P1080.equals(label) || RenditionLabels.P720.equals(label)
                || RenditionLabels.P480.equals(label) || RenditionLabels.P360.equals(label);
    }

    private static String extensionFor(String mime) {
        if (mime == null) return ".bin";
        return switch (mime) {
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a";
            case "audio/ogg" -> ".ogg";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/webm" -> ".weba";
            default -> ".bin";
        };
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }
}
