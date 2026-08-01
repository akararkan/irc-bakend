package ak.dev.irc.app.security.twofa;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP, implemented with pure JDK crypto ({@code javax.crypto.Mac},
 * HmacSHA1) — no external TOTP library (this build is offline). 6 digits,
 * 30-second step. Base32 secret, compatible with Google Authenticator, Authy,
 * 1Password, etc.
 */
@Component
public class TotpProvider {

    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    /** Generate a fresh 160-bit secret, Base32-encoded (no padding). */
    public String generateSecret() {
        byte[] bytes = new byte[20]; // 160 bits
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** The current step index for a given epoch second. */
    public long currentStep(long epochSeconds) {
        return epochSeconds / PERIOD_SECONDS;
    }

    /**
     * Verify a code against a secret, tolerating ±{@code allowedDriftSteps} and
     * enforcing a replay guard: the matched step must be strictly greater than
     * {@code lastAcceptedStep} (pass a negative value if none yet).
     *
     * @return the matched step index if valid, or {@code -1} if not.
     */
    public long verify(String base32Secret, String code, long nowEpochSeconds,
                       int allowedDriftSteps, long lastAcceptedStep) {
        if (code == null) return -1;
        String normalized = code.trim();
        if (!normalized.matches("\\d{" + DIGITS + "}")) return -1;
        long current = currentStep(nowEpochSeconds);
        byte[] key = base32Decode(base32Secret);
        for (int offset = -allowedDriftSteps; offset <= allowedDriftSteps; offset++) {
            long step = current + offset;
            if (step <= lastAcceptedStep) continue;         // replay guard
            if (String.format("%0" + DIGITS + "d", codeForStep(key, step)).equals(normalized)) {
                return step;
            }
        }
        return -1;
    }

    /** Build an {@code otpauth://} provisioning URI for the QR code. */
    public String provisioningUri(String issuer, String accountName, String base32Secret) {
        String label = enc(issuer) + ":" + enc(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    // ── HOTP core (RFC 4226) ──────────────────────────────────────────────────

    private int codeForStep(byte[] key, long step) {
        byte[] data = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return binary % (int) Math.pow(10, DIGITS);
        } catch (Exception ex) {
            throw new IllegalStateException("TOTP computation failed", ex);
        }
    }

    // ── Base32 (RFC 4648, no padding) ──────────────────────────────────────────

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                sb.append(BASE32.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            sb.append(BASE32.charAt(index));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase();
        int buffer = 0, bitsLeft = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int idx = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
