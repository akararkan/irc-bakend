package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.MediaTier;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import ak.dev.irc.app.research.service.S3StorageService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Media re-encode/downscale worker (spec §20.4–20.7). Runs on a small bounded
 * pool — <b>not</b> the API thread — so one malicious 8K file can't degrade API
 * latency for every user (§20.7). Each asset: scan → transcode (image via
 * {@link ImageProcessor}, video via {@link VideoProcessor}) → upload renditions
 * → accumulate {@code storedBytes} → status READY.
 *
 * <p>SEAM: production would use RabbitMQ ({@code media.process}) with retry/DLQ
 * and emit an SSE {@code media.ready}; here the ExecutorService + status polling
 * keep the module self-contained. The full rendition ladder (720/480/360) and
 * HLS packaging are additional ffmpeg runs to add behind {@link VideoProcessor}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final S3StorageService storage;
    private final ImageProcessor imageProcessor;
    private final VideoProcessor videoProcessor;
    private final MediaScanner scanner;
    private final MediaProperties props;

    /** Bounded pool: small core/max, caller-runs on saturation (back-pressure). */
    private final ExecutorService pool = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64),
            r -> { Thread t = new Thread(r, "media-worker"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    void shutdown() { pool.shutdownNow(); }

    /** Enqueue processing of an already-created PROCESSING asset. */
    public void submit(UUID assetId, byte[] original, MediaAssetType type, MediaTier tier) {
        pool.submit(() -> {
            try {
                process(assetId, original, type, tier);
            } catch (Exception ex) {
                log.error("[MEDIA] processing {} failed: {}", assetId, ex.getMessage(), ex);
                fail(assetId, MediaStatus.FAILED_PROCESSING, ex.getMessage());
            }
        });
    }

    private void process(UUID assetId, byte[] original, MediaAssetType type, MediaTier tier) throws Exception {
        // 1. Moderation scan.
        if (!scanner.isClean(original, type.name())) {
            fail(assetId, MediaStatus.FAILED_MODERATION, "Rejected by moderation scan.");
            return;
        }

        List<MediaRendition> renditions = new ArrayList<>();
        long stored = 0;
        Integer width = null, height = null;

        if (type == MediaAssetType.IMAGE) {
            try {
                ImageProcessor.Result r = imageProcessor.process(original, tier.imageLongEdge());
                String key = storage.upload(
                        new BytesMultipartFile(r.bytes(), assetId + "." + r.label(), r.mime()),
                        "media/" + assetId);
                renditions.add(rendition(assetId, r.label(), key, r.bytes().length, r.width(), r.height(), r.mime()));
                stored += r.bytes().length;
                width = r.width();
                height = r.height();
            } catch (IllegalArgumentException bad) {
                fail(assetId, MediaStatus.FAILED_VALIDATION, bad.getMessage());
                return;
            }
        } else if (type.isVideo()) {
            VideoProcessor.Result r = videoProcessor.process(original, tier.videoShortEdge());
            String key = storage.upload(
                    new BytesMultipartFile(r.bytes(), assetId + ".mp4", r.mime()),
                    "media/" + assetId);
            renditions.add(rendition(assetId, r.label(), key, r.bytes().length, r.width(), r.height(), r.mime()));
            stored += r.bytes().length;
            width = r.width();
            height = r.height();
        } else {
            // AUDIO / other — store as-is (transcode to Opus is a seam).
            String key = storage.upload(
                    new BytesMultipartFile(original, assetId + ".bin", "application/octet-stream"),
                    "media/" + assetId);
            renditions.add(rendition(assetId, "original", key, original.length, null, null, "application/octet-stream"));
            stored += original.length;
        }

        renditionRepo.saveAll(renditions);
        complete(assetId, stored, width, height);
        log.info("[MEDIA] asset {} READY — {} rendition(s), {} stored bytes", assetId, renditions.size(), stored);
    }

    private MediaRendition rendition(UUID assetId, String label, String key, long bytes,
                                     Integer w, Integer h, String mime) {
        return MediaRendition.builder()
                .id(new MediaRendition.MediaRenditionId(assetId, label))
                .objectKey(key)
                .url(storage.getPublicUrl(key))
                .bytes(bytes)
                .width(w)
                .height(h)
                .mime(mime)
                .build();
    }

    @Transactional
    protected void complete(UUID assetId, long stored, Integer w, Integer h) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStatus(MediaStatus.READY);
            a.setStoredBytes(stored);
            if (w != null) a.setWidth(w);
            if (h != null) a.setHeight(h);
            a.setPurgeOriginalAt(LocalDateTime.now().plusDays(7)); // §20.6 raw/ retention
            assetRepo.save(a);
        });
    }

    @Transactional
    protected void fail(UUID assetId, MediaStatus status, String message) {
        assetRepo.findById(assetId).ifPresent(a -> {
            a.setStatus(status);
            a.setErrorMessage(message != null && message.length() > 300
                    ? message.substring(0, 300) : message);
            assetRepo.save(a);
        });
    }
}
