package ak.dev.irc.app.media.service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Pure-JDK BlurHash encoder (the standard 4×3-component variant) — produces
 * the compact placeholder string clients paint before any bytes of the real
 * image/poster arrive. Reference algorithm: woltapp/blurhash. No dependencies:
 * the offline build ({@code mvn -o}) cannot fetch a library for this.
 *
 * <p>Input is downsampled to ≤32 px before the DCT so encoding cost is
 * microseconds regardless of source size. Output fits the existing
 * {@code media_assets.blurhash varchar(64)} column (4×3 → 28 chars).</p>
 */
public final class BlurHashEncoder {

    private static final int COMPONENTS_X = 4;
    private static final int COMPONENTS_Y = 3;
    private static final int SAMPLE_EDGE = 32;

    private static final char[] ALPHABET =
            ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~")
                    .toCharArray();

    private BlurHashEncoder() {}

    /** Encode from raw image bytes (JPEG/PNG/…); {@code null} on any failure. */
    public static String encode(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return img == null ? null : encode(img);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Encode from a decoded image; {@code null} on any failure. */
    public static String encode(BufferedImage source) {
        try {
            BufferedImage img = downsample(source);
            int w = img.getWidth(), h = img.getHeight();
            double[][] factors = new double[COMPONENTS_X * COMPONENTS_Y][3];

            // Linear-RGB pixel grid once; cosine bases per component.
            double[][] lin = new double[w * h][3];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    double[] px = lin[y * w + x];
                    px[0] = srgbToLinear((rgb >> 16) & 0xFF);
                    px[1] = srgbToLinear((rgb >> 8) & 0xFF);
                    px[2] = srgbToLinear(rgb & 0xFF);
                }
            }
            for (int j = 0; j < COMPONENTS_Y; j++) {
                for (int i = 0; i < COMPONENTS_X; i++) {
                    double norm = (i == 0 && j == 0) ? 1 : 2;
                    double r = 0, g = 0, b = 0;
                    for (int y = 0; y < h; y++) {
                        double cy = Math.cos(Math.PI * j * y / h);
                        for (int x = 0; x < w; x++) {
                            double basis = norm * Math.cos(Math.PI * i * x / w) * cy;
                            double[] px = lin[y * w + x];
                            r += basis * px[0];
                            g += basis * px[1];
                            b += basis * px[2];
                        }
                    }
                    double scale = 1.0 / (w * h);
                    factors[j * COMPONENTS_X + i][0] = r * scale;
                    factors[j * COMPONENTS_X + i][1] = g * scale;
                    factors[j * COMPONENTS_X + i][2] = b * scale;
                }
            }

            StringBuilder hash = new StringBuilder();
            encode83(hash, COMPONENTS_X - 1 + (COMPONENTS_Y - 1) * 9, 1);

            double maxAc = 0;
            for (int i = 1; i < factors.length; i++) {
                for (double v : factors[i]) maxAc = Math.max(maxAc, Math.abs(v));
            }
            int quantMax = clamp((int) Math.floor(maxAc * 166 - 0.5), 0, 82);
            double acScale = (quantMax + 1) / 166.0;
            encode83(hash, quantMax, 1);

            double[] dc = factors[0];
            encode83(hash, (linearToSrgb(dc[0]) << 16) + (linearToSrgb(dc[1]) << 8)
                    + linearToSrgb(dc[2]), 4);
            for (int i = 1; i < factors.length; i++) {
                encode83(hash,
                        quantAc(factors[i][0], acScale) * 19 * 19
                                + quantAc(factors[i][1], acScale) * 19
                                + quantAc(factors[i][2], acScale), 2);
            }
            return hash.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static BufferedImage downsample(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= SAMPLE_EDGE && h <= SAMPLE_EDGE) {
            return toIntRgb(src);
        }
        double scale = (double) SAMPLE_EDGE / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage toIntRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int quantAc(double value, double acScale) {
        return clamp((int) Math.floor(signPow(value / acScale) * 9 + 9.5), 0, 18);
    }

    /** signPow(v, 0.5) from the reference implementation. */
    private static double signPow(double v) {
        return Math.copySign(Math.sqrt(Math.abs(v)), v);
    }

    private static double srgbToLinear(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSrgb(double linear) {
        double v = Math.max(0, Math.min(1, linear));
        double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) (s * 255 + 0.5);
    }

    private static void encode83(StringBuilder sb, int value, int length) {
        for (int i = length - 1; i >= 0; i--) {
            int digit = (value / pow83(i)) % 83;
            sb.append(ALPHABET[digit]);
        }
    }

    private static int pow83(int exp) {
        int result = 1;
        for (int i = 0; i < exp; i++) result *= 83;
        return result;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
