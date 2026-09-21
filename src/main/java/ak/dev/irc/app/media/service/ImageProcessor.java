package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Pure-JDK image transcoder (spec §20.4–20.5). Produces the full rendition set
 * for an {@link ImagePlan}: display rungs, list thumbnail and square micro
 * thumb — never upscaling, and <b>stripping all metadata</b> by redrawing the
 * pixels into fresh {@link BufferedImage}s (EXIF/GPS never survive, a
 * mandatory privacy control per §20.5).
 *
 * <p>Correctness notes (each fixes a past bug):
 * <ul>
 *   <li>the megapixel bomb guard reads dimensions from the stream header
 *       <i>before</i> any pixel decode;</li>
 *   <li>the EXIF orientation tag is parsed (pure JDK) and applied to the
 *       pixels, so phone portraits come out upright;</li>
 *   <li>transparent sources are composited on <b>white</b> for JPEG output
 *       (not flattened to black) and keep their alpha in the WebP source;</li>
 *   <li>downscales &gt;2× go through progressive halving (bilinear per step)
 *       instead of one aliasing-prone jump.</li>
 * </ul></p>
 *
 * <p>WebP encoding itself lives in {@link StillWebpEncoder} (ffmpeg); this
 * class only prepares the encoder input — the scaled JPEG bytes, or a lossless
 * PNG when the source has alpha to preserve.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageProcessor {

    private final MediaProperties props;

    /**
     * One produced rendition.
     *
     * @param webpLabel  label for the WebP twin the pipeline should attempt,
     *                   or {@code null} when this rung gets no WebP
     * @param pngForWebp lossless WebP-encoder input when the source has alpha;
     *                   {@code null} → feed the JPEG bytes to the encoder
     */
    public record Variant(String label, String webpLabel, byte[] jpeg, byte[] pngForWebp,
                          int width, int height) {}

    /** Header-only source facts — no pixel decode has happened yet. */
    public record SourceMeta(int width, int height, int orientation, String format) {}

    // ── Probe (bomb guard BEFORE decode) ─────────────────────────────────────

    /**
     * Read dimensions/format from the stream header and enforce the megapixel
     * guard before a single pixel is decoded.
     *
     * @throws IllegalArgumentException for unsupported bytes or an oversized image
     */
    public SourceMeta probe(byte[] input) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Unsupported or corrupt image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                long megapixels = (long) w * h / 1_000_000L;
                if (megapixels > props.getLimits().getMaxInputMegapixels()) {
                    throw new IllegalArgumentException("Image exceeds the "
                            + props.getLimits().getMaxInputMegapixels() + " MP decode limit.");
                }
                String format;
                try { format = reader.getFormatName(); } catch (Exception e) { format = "unknown"; }
                return new SourceMeta(w, h, readExifOrientation(input), format);
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported or corrupt image.");
        }
    }

    // ── Full pipeline ────────────────────────────────────────────────────────

    /**
     * Decode once, normalize orientation, then derive every rendition in the
     * plan — each from the previous (larger) intermediate, never re-decoding
     * and never upscaling.
     *
     * @throws IllegalArgumentException for undecodable/oversized input
     */
    public List<Variant> processAll(byte[] input, ImagePlan plan) throws Exception {
        SourceMeta meta = probe(input);

        BufferedImage src = ImageIO.read(new ByteArrayInputStream(input));
        if (src == null) {
            throw new IllegalArgumentException("Unsupported or corrupt image.");
        }
        boolean hasAlpha = src.getColorModel().hasAlpha();
        src = normalizeOrientation(src, meta.orientation(), hasAlpha);

        int srcLongEdge = Math.max(src.getWidth(), src.getHeight());
        List<Variant> out = new ArrayList<>();
        BufferedImage cur = src;

        // Display rungs, largest first. Skip a rung when the source can't
        // exceed the next rung down (that rung produces the identical size).
        List<ImagePlan.Rung> rungs = plan.rungs();
        for (int i = 0; i < rungs.size(); i++) {
            ImagePlan.Rung rung = rungs.get(i);
            boolean hasSmaller = i + 1 < rungs.size();
            if (hasSmaller && srcLongEdge <= rungs.get(i + 1).longEdge()) {
                continue;
            }
            BufferedImage img;
            if (plan.squarePrimary()) {
                BufferedImage square = centerSquare(cur, hasAlpha);
                img = progressiveScale(square, Math.min(rung.longEdge(), square.getWidth()),
                        Math.min(rung.longEdge(), square.getHeight()), hasAlpha);
            } else {
                int[] dims = fit(cur.getWidth(), cur.getHeight(), rung.longEdge());
                img = progressiveScale(cur, dims[0], dims[1], hasAlpha);
            }
            out.add(variant(rung.jpegLabel(), plan.webp() ? rung.webpLabel() : null, img, hasAlpha));
            cur = img;   // next rung derives from this intermediate
        }

        if (plan.thumb()) {
            int[] dims = fit(cur.getWidth(), cur.getHeight(), props.getImage().getThumbEdge());
            BufferedImage img = progressiveScale(cur, dims[0], dims[1], hasAlpha);
            out.add(variant(ak.dev.irc.app.media.enums.RenditionLabels.THUMB_320, null, img, hasAlpha));
            cur = img;
        }
        if (plan.squareThumb()) {
            BufferedImage square = centerSquare(cur, hasAlpha);
            int edge = Math.min(props.getImage().getSquareThumbEdge(), square.getWidth());
            BufferedImage img = progressiveScale(square, edge, edge, hasAlpha);
            out.add(variant(ak.dev.irc.app.media.enums.RenditionLabels.THUMB_SQ150, null, img, hasAlpha));
        }

        log.debug("[MEDIA-IMG] plan={} src={}x{} orient={} alpha={} → {} variants",
                plan.name(), meta.width(), meta.height(), meta.orientation(), hasAlpha, out.size());
        return out;
    }

    private Variant variant(String label, String webpLabel, BufferedImage img, boolean hasAlpha) throws Exception {
        byte[] jpeg = encodeJpeg(img, props.getImage().getJpegQuality() / 100f);
        byte[] png = (webpLabel != null && hasAlpha) ? encodePng(img) : null;
        return new Variant(label, webpLabel, jpeg, png, img.getWidth(), img.getHeight());
    }

    /** Target dims for a long-edge cap, preserving aspect and never upscaling. */
    private static int[] fit(int w, int h, int maxLongEdge) {
        int longEdge = Math.max(w, h);
        if (longEdge <= maxLongEdge) return new int[]{w, h};
        double scale = (double) maxLongEdge / longEdge;
        return new int[]{Math.max(1, (int) Math.round(w * scale)),
                         Math.max(1, (int) Math.round(h * scale))};
    }

    // ── Orientation ──────────────────────────────────────────────────────────

    /**
     * EXIF Orientation (tag 0x0112) from a JPEG's APP1 segment — pure JDK,
     * fully bounds-checked; any anomaly returns 1 (no transform). Non-JPEG
     * bytes return 1 (PNG/GIF/BMP carry no EXIF orientation worth honoring).
     */
    static int readExifOrientation(byte[] b) {
        try {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int i = 2;
            while (i + 4 <= b.length) {
                if ((b[i] & 0xFF) != 0xFF) return 1;         // lost sync
                int marker = b[i + 1] & 0xFF;
                if (marker == 0xFF) { i++; continue; }        // fill byte
                if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                    i += 2; continue;                          // standalone marker
                }
                if (marker == 0xDA || marker == 0xD9) return 1; // image data / EOI — no APP1 seen
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (len < 2 || i + 2 + len > b.length) return 1;
                if (marker == 0xE1 && len >= 2 + 6 + 8) {
                    int p = i + 4;
                    if (b[p] == 'E' && b[p + 1] == 'x' && b[p + 2] == 'i' && b[p + 3] == 'f'
                            && b[p + 4] == 0 && b[p + 5] == 0) {
                        int orientation = readTiffOrientation(b, p + 6, i + 2 + len);
                        return (orientation >= 1 && orientation <= 8) ? orientation : 1;
                    }
                }
                i += 2 + len;
            }
            return 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /** Walk TIFF IFD0 within [tiff, end) looking for the Orientation SHORT. */
    private static int readTiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) return 1;
        boolean le;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') le = true;
        else if (b[tiff] == 'M' && b[tiff + 1] == 'M') le = false;
        else return 1;
        if (u16(b, tiff + 2, le) != 42) return 1;
        long ifdOffset = u32(b, tiff + 4, le);
        long ifd = tiff + ifdOffset;
        if (ifd < tiff || ifd + 2 > end) return 1;
        int count = u16(b, (int) ifd, le);
        if (count < 0 || count > 512) return 1;
        for (int e = 0; e < count; e++) {
            int entry = (int) ifd + 2 + e * 12;
            if (entry + 12 > end) return 1;
            int tag = u16(b, entry, le);
            int type = u16(b, entry + 2, le);
            if (tag == 0x0112 && type == 3) {
                return u16(b, entry + 8, le);   // SHORT count 1 is stored inline
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int off, boolean le) {
        int a = b[off] & 0xFF, c = b[off + 1] & 0xFF;
        return le ? (c << 8) | a : (a << 8) | c;
    }

    private static long u32(byte[] b, int off, boolean le) {
        long a = b[off] & 0xFF, c = b[off + 1] & 0xFF, d = b[off + 2] & 0xFF, f = b[off + 3] & 0xFF;
        return le ? (f << 24) | (d << 16) | (c << 8) | a : (a << 24) | (c << 16) | (d << 8) | f;
    }

    /** Rotate/flip pixels so orientation becomes 1. Returns {@code src} unchanged for 1. */
    private static BufferedImage normalizeOrientation(BufferedImage src, int orientation, boolean hasAlpha) {
        if (orientation <= 1 || orientation > 8) return src;
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage dst = canvas(swap ? h : w, swap ? w : h, hasAlpha);
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        Graphics2D g = dst.createGraphics();
        hints(g);
        g.drawImage(src, t, null);
        g.dispose();
        return dst;
    }

    // ── Scaling ──────────────────────────────────────────────────────────────

    /**
     * Progressive halving: bilinear looks fine up to ~2× reduction, so halve
     * repeatedly and finish with one fractional step. Intermediates keep alpha
     * when the source has it (flattening happens only at JPEG encode).
     */
    private static BufferedImage progressiveScale(BufferedImage src, int tw, int th, boolean hasAlpha) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == tw && h == th) return src;
        BufferedImage cur = src;
        while (w / 2 >= tw && h / 2 >= th) {
            w = Math.max(tw, w / 2);
            h = Math.max(th, h / 2);
            cur = draw(cur, w, h, hasAlpha);
        }
        if (w != tw || h != th) {
            cur = draw(cur, tw, th, hasAlpha);
        }
        return cur;
    }

    /** Centered square crop — a fresh copy, never a raster-sharing subimage. */
    private static BufferedImage centerSquare(BufferedImage src, boolean hasAlpha) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == h) return src;
        int s = Math.min(w, h);
        int x = (w - s) / 2, y = (h - s) / 2;
        BufferedImage dst = canvas(s, s, hasAlpha);
        Graphics2D g = dst.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, s, s, x, y, x + s, y + s, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage draw(BufferedImage src, int w, int h, boolean hasAlpha) {
        BufferedImage dst = canvas(w, h, hasAlpha);
        Graphics2D g = dst.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage canvas(int w, int h, boolean hasAlpha) {
        return new BufferedImage(w, h,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    // ── Encoding ─────────────────────────────────────────────────────────────

    /** Progressive JPEG; transparent pixels are composited on white first. */
    private byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        BufferedImage rgb = image;
        if (image.getColorModel().hasAlpha()) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPEG writer available.");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            }
            if (param.canWriteProgressive()) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /** Lossless PNG — the alpha-preserving WebP encoder input. */
    private static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", baos)) {
            throw new IllegalStateException("No PNG writer available.");
        }
        return baos.toByteArray();
    }
}
