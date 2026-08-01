package ak.dev.irc.app.security.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Small pure-JDK hashing helpers used across the security module (spec §2, §3).
 * No external crypto library — {@code javax.crypto} covers HMAC-SHA256, and
 * {@link MessageDigest} covers SHA-256.
 */
public final class Hashing {

    private Hashing() {}

    /** {@code HMAC-SHA256(message, key)} as lowercase hex. */
    public static String hmacSha256Hex(String message, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    (key == null ? "" : key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((message == null ? "" : message).getBytes(StandardCharsets.UTF_8));
            return toHex(out);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    /** {@code SHA-256(input)} as lowercase hex. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return toHex(out);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Constant-time comparison of two hex strings — avoids timing side-channels. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
