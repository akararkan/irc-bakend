package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.enums.RenditionLabels;

import java.util.ArrayList;
import java.util.List;

/**
 * A named set of image renditions to produce for one upload surface. Display
 * rungs are listed largest-first; the processor derives each from the previous
 * intermediate and skips any rung the source can't fill beyond the next one
 * down (never upscale, never emit duplicate sizes).
 *
 * @param name          plan name for logs
 * @param rungs         display rungs, descending long edge
 * @param squarePrimary when true the single rung is a center-square crop
 *                      (avatars/channel photos)
 * @param thumb         produce the {@code thumb_320} list thumbnail
 * @param squareThumb   produce the {@code thumb_sq150} square micro thumb
 * @param webp          produce WebP twins for display rungs (ffmpeg permitting)
 */
public record ImagePlan(String name, List<Rung> rungs, boolean squarePrimary,
                        boolean thumb, boolean squareThumb, boolean webp) {

    /** One display rung: JPEG label, optional WebP twin label, target long edge. */
    public record Rung(String jpegLabel, String webpLabel, int longEdge) {}

    /** Zoom + feed + both thumbs — posts, research media, QnA attachments. */
    public static ImagePlan full(MediaProperties p) {
        return new ImagePlan("full", List.of(
                new Rung(RenditionLabels.JPEG_1440, RenditionLabels.WEBP_1440, 1440),
                new Rung(RenditionLabels.JPEG_1080, RenditionLabels.WEBP_1080, 1080)),
                false, true, true, p.getImage().isWebpEnabled());
    }

    /** Feed class only — stories, covers, sound covers, comment/answer media. */
    public static ImagePlan feed(MediaProperties p) {
        return new ImagePlan("feed", List.of(
                new Rung(RenditionLabels.JPEG_1080, RenditionLabels.WEBP_1080, 1080)),
                false, true, true, p.getImage().isWebpEnabled());
    }

    /** Chat display class (existing {@code chat-max-long-edge} config). */
    public static ImagePlan chat(MediaProperties p) {
        return new ImagePlan("chat", List.of(
                new Rung(RenditionLabels.JPEG_1280, RenditionLabels.WEBP_1280,
                        p.getImage().getChatMaxLongEdge())),
                false, true, true, p.getImage().isWebpEnabled());
    }

    /** Square profile crop ({@code profile-edge}) + micro thumb — avatars, channel photos. */
    public static ImagePlan profile(MediaProperties p) {
        return new ImagePlan("profile", List.of(
                new Rung(RenditionLabels.AVATAR_512, null, p.getImage().getProfileEdge())),
                true, false, true, false);
    }

    /**
     * Clamp every rung's edge to {@code maxLongEdge} (tier enforcement),
     * dropping rungs that collapse onto the next one down.
     */
    public ImagePlan clampTo(int maxLongEdge) {
        List<Rung> out = new ArrayList<>();
        for (Rung r : rungs) {
            int edge = Math.min(r.longEdge(), maxLongEdge);
            if (!out.isEmpty() && out.get(out.size() - 1).longEdge() <= edge) {
                // The larger rung collapsed onto this one — keep the smaller,
                // honest label (jpeg_1080 at 1080, not jpeg_1440 at 1080).
                out.remove(out.size() - 1);
            }
            out.add(new Rung(r.jpegLabel(), r.webpLabel(), edge));
        }
        return new ImagePlan(name, out, squarePrimary, thumb, squareThumb, webp);
    }
}
