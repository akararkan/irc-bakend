package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.research.service.S3StorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous image processing: decode → variants ({@link ImageProcessor}) →
 * WebP twins ({@link StillWebpEncoder}, best-effort) → deterministic
 * {@code media/{assetId}/{label}.{ext}} keys → one transaction persisting the
 * rendition rows and flipping the asset READY.
 *
 * <p>Runs on the request thread (an image resize is fast); a small semaphore
 * bounds concurrent decodes because a worst-case decode is ~400 MB of heap.
 * The WebP twins respect {@code media.image.sync-budget-ms} — past the budget
 * the remaining twins are skipped, never the JPEGs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePipeline {

    private static final long ACQUIRE_TIMEOUT_MS = 10_000;

    private final ImageProcessor imageProcessor;
    private final StillWebpEncoder webpEncoder;
    private final MediaAssetStatusService statusService;
    private final S3StorageService storage;
    private final MediaProperties props;

    private Semaphore decodeSlots;

    @PostConstruct
    void init() {
        decodeSlots = new Semaphore(Math.max(1, props.getImage().getMaxConcurrent()));
    }

    /**
     * @param width/height of the primary (largest display) rendition
     */
    public record ImageOutcome(List<MediaRendition> renditions, int width, int height,
                               long storedBytes) {}

    /**
     * Process {@code input} for an already-created asset row and persist
     * everything. On any thrown error the caller owns cleanup (asset delete).
     *
     * @throws IllegalArgumentException undecodable/oversized input (→ 400)
     * @throws IllegalStateException    decode slots exhausted (→ retry later)
     */
    public ImageOutcome processAndPersist(UUID assetId, byte[] input, ImagePlan plan) throws Exception {
        long start = System.currentTimeMillis();
        List<ImageProcessor.Variant> variants;
        if (!decodeSlots.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Image processing is at capacity — try again shortly.");
        }
        try {
            variants = imageProcessor.processAll(input, plan);
        } finally {
            decodeSlots.release();
        }

        List<MediaRendition> renditions = new ArrayList<>();
        long stored = 0;
        boolean webpWanted = plan.webp() && webpEncoder.available();
        for (ImageProcessor.Variant v : variants) {
            String jpegKey = "media/" + assetId + "/" + v.label() + ".jpg";
            storage.putBytes(v.jpeg(), jpegKey, "image/jpeg");
            renditions.add(rendition(assetId, v.label(), jpegKey, v.jpeg().length,
                    v.width(), v.height(), "image/jpeg"));
            stored += v.jpeg().length;

            if (webpWanted && v.webpLabel() != null) {
                if (System.currentTimeMillis() - start > props.getImage().getSyncBudgetMs()) {
                    log.debug("[MEDIA-IMG] sync budget exceeded — skipping remaining WebP twins for {}", assetId);
                    webpWanted = false;
                    continue;
                }
                byte[] webp = webpEncoder.encode(v.pngForWebp() != null ? v.pngForWebp() : v.jpeg());
                // Only keep a twin that actually saves bytes over its JPEG.
                if (webp != null && webp.length < v.jpeg().length) {
                    String webpKey = "media/" + assetId + "/" + v.webpLabel() + ".webp";
                    storage.putBytes(webp, webpKey, "image/webp");
                    renditions.add(rendition(assetId, v.webpLabel(), webpKey, webp.length,
                            v.width(), v.height(), "image/webp"));
                    stored += webp.length;
                }
            }
        }

        ImageProcessor.Variant primary = variants.get(0);
        // Placeholder hash from the smallest FULL-FRAME variant — clients
        // paint the hash into the image's real aspect box, so the square
        // center-crop (thumb_sq150) would encode the wrong picture. Decoding
        // a thumb-sized JPEG plus a 32px DCT is microseconds either way.
        String blurhash = BlurHashEncoder.encode(blurhashSource(variants).jpeg());
        statusService.finalizeReadyWithRenditions(assetId, renditions, stored,
                primary.width(), primary.height(), blurhash);
        log.info("[MEDIA-IMG] asset {} READY — {} rendition(s), {} bytes, {} ms",
                assetId, renditions.size(), stored, System.currentTimeMillis() - start);
        return new ImageOutcome(renditions, primary.width(), primary.height(), stored);
    }

    /** Smallest full-frame variant: thumb_320 when present, else the last non-square-crop, else the last. */
    private static ImageProcessor.Variant blurhashSource(List<ImageProcessor.Variant> variants) {
        ImageProcessor.Variant fallback = null;
        for (ImageProcessor.Variant v : variants) {
            if (ak.dev.irc.app.media.enums.RenditionLabels.THUMB_320.equals(v.label())) return v;
            if (!ak.dev.irc.app.media.enums.RenditionLabels.THUMB_SQ150.equals(v.label())) fallback = v;
        }
        return fallback != null ? fallback : variants.get(variants.size() - 1);
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
}
