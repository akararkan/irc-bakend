package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * Pure-JDK image transcoder (spec §20.4–20.5). Downscales to the platform cap
 * (never upscales), re-encodes to progressive JPEG at the configured quality,
 * and — critically — <b>strips all metadata</b> by redrawing the pixels into a
 * fresh {@link BufferedImage} (EXIF/GPS never survive, a mandatory privacy
 * control per §20.5).
 *
 * <p>SEAM: AVIF/WebP output needs native ImageIO plugins (not on the offline
 * classpath), so the guaranteed output is JPEG. Swap in a libvips/AVIF encoder
 * behind this component when the plugins are available.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageProcessor {

    private final MediaProperties props;

    public record Result(byte[] bytes, int width, int height, String mime, String label) {}

    /**
     * @param maxLongEdge cap for the long edge (e.g. tier/config value)
     * @throws java.io.IOException if the bytes are not a decodable image
     * @throws IllegalArgumentException if the image exceeds the megapixel bomb guard
     */
    public Result process(byte[] input, int maxLongEdge) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(input));
        if (src == null) {
            throw new IllegalArgumentException("Unsupported or corrupt image.");
        }
        long megapixels = (long) src.getWidth() * src.getHeight() / 1_000_000L;
        if (megapixels > props.getLimits().getMaxInputMegapixels()) {
            throw new IllegalArgumentException("Image exceeds the "
                    + props.getLimits().getMaxInputMegapixels() + " MP decode limit.");
        }

        int cap = Math.min(maxLongEdge, props.getImage().getMaxLongEdge());
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);

        double scale = longEdge > cap ? (double) cap / longEdge : 1.0; // never upscale
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));

        // Redraw into a fresh RGB canvas — drops alpha, EXIF, GPS, everything.
        BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();

        byte[] out = encodeJpeg(dst, props.getImage().getJpegQuality() / 100f);
        log.debug("[MEDIA-IMG] {}x{} → {}x{}, {} → {} bytes",
                w, h, tw, th, input.length, out.length);
        return new Result(out, tw, th, "image/jpeg", "jpeg");
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
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
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
