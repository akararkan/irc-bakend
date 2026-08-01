package ak.dev.irc.app.security.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM authenticated encryption for secrets that must be <em>decryptable</em>
 * — the TOTP shared secret (spec §12), which cannot be hashed because it has to
 * be re-read to verify codes. Pure JDK ({@code javax.crypto}); the data key
 * comes from the environment ({@code app.security.twofa.secret-key}), never the
 * database.
 *
 * <p>Ciphertext format: {@code base64(iv[12] || ciphertext || tag)}. A fresh
 * 96-bit IV per encryption is mandatory for GCM.</p>
 *
 * <p>If no key is configured, a process-ephemeral key is derived so the app
 * still boots in dev — but secrets then do not survive a restart, which is
 * logged loudly. Set the env var in any real deployment.</p>
 */
@Slf4j
@Component
public class SecretCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.security.twofa.secret-key:}") String configuredKey) {
        byte[] keyBytes;
        if (configuredKey != null && !configuredKey.isBlank()) {
            // Derive a stable 256-bit key from the configured secret (any length in).
            keyBytes = sha256(configuredKey.getBytes(StandardCharsets.UTF_8));
        } else {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            keyBytes = ephemeral;
            log.warn("[2FA-CIPHER] app.security.twofa.secret-key is not set — using a "
                    + "process-ephemeral key. Enrolled 2FA secrets will NOT survive a restart. "
                    + "Set TWOFA_AES_KEY in any real deployment.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("2FA secret encryption failed", ex);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("2FA secret decryption failed", ex);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
