package ak.dev.irc.app.moderation.image;

import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.MediaMessages;
import ak.dev.irc.app.media.enums.MediaSurface;
import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.client.InferenceUnavailableException;
import ak.dev.irc.app.moderation.engine.ModerationSettingsService;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.entity.ModerationCaseField;
import ak.dev.irc.app.moderation.enums.FallbackPolicy;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.enums.ModerationVerdict;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The synchronous NSFW gate for image bytes — the whole decision path of
 * docs/moderation/image-moderation.md, called from {@code MediaIngestService}
 * for every uploaded image, GIF, and (optionally) extracted video poster.
 *
 * <p>Unlike the text pipeline there is no quarantine-then-publish here: the
 * bytes are in hand and the model answers in tens of milliseconds, so the
 * decision is made inline, before anything is stored.</p>
 *
 * <ul>
 *   <li><b>nsfw ≥ block threshold</b> — the upload is rejected with
 *       {@code MEDIA_NSFW_BLOCKED}; nothing is ever stored.</li>
 *   <li><b>review ≤ nsfw &lt; block</b> — the upload proceeds (availability
 *       first for the uncertain middle), and the caller records a
 *       {@link ModeratedEntityType#MEDIA_IMAGE} case so a human sees it in the
 *       existing review queue. Rejecting there deletes the asset via
 *       {@code MediaImageModerationApplier}.</li>
 *   <li><b>scorer down</b> — the configured {@link FallbackPolicy}:
 *       {@code FAIL_OPEN_SHADOW} publishes and queues for review,
 *       {@code FAIL_CLOSED} refuses the upload with a 503.</li>
 *   <li><b>bytes not decodable</b> — allowed through unscored; the media
 *       pipeline's decoders are the arbiter of what is a valid image and will
 *       reject the file themselves.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageModerationGate {

    /** Field name on review cases — lets the queue label the row "image". */
    private static final String FIELD_IMAGE = "image";
    private static final String REASON_MODEL = "MODEL";
    private static final String REASON_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    private final ImageModerationClient client;
    private final ModerationSettingsService settings;
    private final ModerationCaseRepository caseRepository;
    private final ModerationProperties properties;

    /**
     * Screens one image. Returns for ALLOW/REVIEW; throws for a confident block
     * or for scorer-down under {@code FAIL_CLOSED}. The caller is responsible
     * for calling {@link #recordReviewCase} once the asset exists, when
     * {@link ImageScreenResult#needsReview()}.
     */
    public ImageScreenResult screen(byte[] bytes, MediaSurface surface, UUID ownerId) {
        if (!settings.imageEnabled()) {
            return ImageScreenResult.skipped();
        }
        ImageScoreResult score;
        try {
            // null budget → the client-wide ceiling (app.moderation.image.timeout-ms).
            score = client.score(prescale(bytes), null);
        } catch (ImageUnscorableException unscorable) {
            // Corrupt/undecodable input — not a moderation question. Let the
            // media pipeline reject it with its own user-facing error.
            log.debug("[MODERATION-IMG] unscorable upload on {}: {}",
                    surface, unscorable.getMessage());
            return ImageScreenResult.skipped();
        } catch (InferenceUnavailableException down) {
            return onScorerDown(surface, down);
        }

        double block = settings.imageBlockThreshold();
        double review = settings.imageReviewThreshold();

        if (score.nsfw() >= block) {
            log.info("[MODERATION-IMG] blocked upload on {} — nsfw={} (block≥{})",
                    surface, score.nsfw(), block);
            throw new BadRequestException(MediaMessages.MEDIA_NSFW_BLOCKED_MSG,
                    MediaMessages.MEDIA_NSFW_BLOCKED);
        }
        if (score.nsfw() >= review) {
            log.info("[MODERATION-IMG] review band on {} — nsfw={} (review≥{})",
                    surface, score.nsfw(), review);
            return new ImageScreenResult(ModerationVerdict.REVIEW, score.nsfw(),
                    score.modelVersion(), true, REASON_MODEL);
        }
        return new ImageScreenResult(ModerationVerdict.APPROVE, score.nsfw(),
                score.modelVersion(), true, REASON_MODEL);
    }

    private ImageScreenResult onScorerDown(MediaSurface surface, InferenceUnavailableException down) {
        FallbackPolicy policy = settings.imageFallback();
        if (policy == FallbackPolicy.FAIL_CLOSED) {
            log.warn("[MODERATION-IMG] scorer unavailable, FAIL_CLOSED — refusing upload on {}: {}",
                    surface, down.getMessage());
            throw new AppException(MediaMessages.MEDIA_MODERATION_UNAVAILABLE_MSG,
                    HttpStatus.SERVICE_UNAVAILABLE, MediaMessages.MEDIA_MODERATION_UNAVAILABLE);
        }
        // FAIL_OPEN_SHADOW: publish, but flag for priority human review — the
        // same contract the text pipeline gives stories on an SLA breach.
        log.warn("[MODERATION-IMG] scorer unavailable, FAIL_OPEN_SHADOW — publishing "
                + "unscreened upload on {} for review: {}", surface, down.getMessage());
        return new ImageScreenResult(ModerationVerdict.REVIEW, null,
                client.stats().modelVersion(), false, REASON_MODEL_UNAVAILABLE);
    }

    /** Whether video poster frames should be screened at all. */
    public boolean videoPostersEnabled() {
        return settings.imageVideoPosters();
    }

    /**
     * Shrinks the payload before it crosses the wire: the model resizes to
     * 224×224 internally, so a multi-megabyte original buys zero accuracy over a
     * ~50KB thumbnail while costing base64 encode, transfer, JSON parse and PIL
     * decode of the full-size file — this is the dominant latency term on the
     * upload path. Formats ImageIO cannot decode (WebP/HEIC) fall through
     * unchanged and the scorer's PIL handles the original; any hiccup here must
     * degrade to "send the original", never to "skip screening".
     */
    private byte[] prescale(byte[] bytes) {
        int maxDim = properties.getImage().getPrescaleMaxDim();
        if (maxDim <= 0 || bytes == null) return bytes;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) return bytes;
            int longest = Math.max(src.getWidth(), src.getHeight());
            if (longest <= maxDim) return bytes;
            double factor = maxDim / (double) longest;
            int w = Math.max(1, (int) Math.round(src.getWidth() * factor));
            int h = Math.max(1, (int) Math.round(src.getHeight() * factor));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                // Flatten transparency onto white — JPEG has no alpha channel.
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.drawImage(src, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            ImageIO.write(scaled, "jpg", out);
            return out.size() > 0 ? out.toByteArray() : bytes;
        } catch (Exception ex) {
            log.debug("[MODERATION-IMG] prescale failed, sending original: {}", ex.getMessage());
            return bytes;
        }
    }

    /**
     * Files a review-band image into the existing admin queue as an
     * {@code IN_REVIEW} {@link ModerationCase} (entityRef = media assetId).
     * Saved directly — no PENDING hold, no worker, no author notification;
     * the image is already public and the author is only told if a human
     * rejects it.
     *
     * <p>Chat uploads are exempt by the same policy that redacts
     * {@code CHAT_MESSAGE} text from staff: private correspondence does not go
     * in front of humans. The block threshold still applies to chat —
     * confidently explicit DMs are refused at upload — only the
     * uncertain-middle queue is skipped.</p>
     */
    @Transactional
    public void recordReviewCase(UUID assetId, String imageUrl, UUID ownerId,
                                 MediaSurface surface, ImageScreenResult screen) {
        if (assetId == null || screen == null || !screen.needsReview()) return;
        if (surface == MediaSurface.CHAT_MEDIA) {
            log.debug("[MODERATION-IMG] review case skipped for chat upload {} (private)", assetId);
            return;
        }
        try {
            double nsfw = screen.nsfwScore() == null ? 0.0 : screen.nsfwScore();
            ModerationCase reviewCase = ModerationCase.builder()
                    .entityType(ModeratedEntityType.MEDIA_IMAGE)
                    .entityRef(assetId.toString())
                    .authorId(ownerId)
                    .status(ModerationStatus.IN_REVIEW)
                    .decidedAt(LocalDateTime.now())
                    .decidedBy("system")
                    .modelVersion(screen.modelVersion())
                    .maxLabel("nsfw")
                    .maxScore(nsfw)
                    .reasonCode(screen.reasonCode())
                    .slaBreached(!screen.scored())
                    .build();
            reviewCase.addField(ModerationCaseField.builder()
                    .fieldName(FIELD_IMAGE)
                    .text(imageUrl == null ? "(image url unavailable)" : imageUrl)
                    .verdict(ModerationVerdict.REVIEW)
                    .maxLabel("nsfw")
                    .maxScore(nsfw)
                    .scoresJson(screen.scored()
                            ? String.format(Locale.ROOT,
                                    "{\"nsfw\":%.4f,\"normal\":%.4f}", nsfw, 1 - nsfw)
                            : null)
                    .build());
            caseRepository.save(reviewCase);
            log.info("[MODERATION-IMG] review case {} filed for asset {} (nsfw={}, surface={})",
                    reviewCase.getId(), assetId, screen.nsfwScore(), surface);
        } catch (Exception ex) {
            // Filing the case is best-effort: the image is already published by
            // policy — losing the queue entry must not fail the upload.
            log.error("[MODERATION-IMG] failed to file review case for asset {}: {}",
                    assetId, ex.getMessage());
        }
    }

    /** Ops panel view of the image scorer, mirroring the text model panel. */
    public Map<String, Object> health() {
        ImageModerationClient.Health health = client.health();
        ImageModerationClient.Stats stats = client.stats();
        return Map.of(
                "imageInferenceUp", health.up(),
                "imageInferenceError", health.error() == null ? "" : health.error(),
                "imageModelVersion", health.modelVersion(),
                "imageCircuit", stats.circuit(),
                "imageCalls", stats.calls(),
                "imageFailures", stats.failures(),
                "imageAvgLatencyMs", stats.avgLatencyMs());
    }
}
