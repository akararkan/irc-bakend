package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Stateless HMAC-signed playback URLs for private media — no DB row, no Redis
 * entry, verification is one SHA-256 HMAC. Off by default; enforcement applies
 * only to object-key prefixes listed in
 * {@code media.serving.protected-prefixes}, so every historical public URL
 * keeps working when the feature is enabled for a new prefix.
 *
 * <p>Token shape appended to a media URL:
 * {@code ?exp={epochSeconds}&sig=base64url(HMAC_SHA256(secret, key|exp))}.</p>
 *
 * <p><b>Scope limits (read before protecting a prefix):</b> (1) nothing calls
 * {@link #sign} yet — a surface adopting protection must sign the URLs it
 * emits, or every request 403s fail-closed; (2) per-URL signatures cannot
 * protect an HLS tree: manifests reference children by RELATIVE URI and
 * query params do not propagate, so protect only non-HLS prefixes (e.g.
 * originals/documents). Protecting HLS needs a path-token or edge-level
 * (CDN token auth) scheme instead.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaSignedUrlService {

    private static final String ALGORITHM = "HmacSHA256";

    private final MediaProperties props;

    /** Signing is live only when switched on AND a secret is configured. */
    public boolean enabled() {
        MediaProperties.Serving s = props.getServing();
        return s.isSignedUrlsEnabled()
                && s.getSigningSecret() != null && !s.getSigningSecret().isBlank();
    }

    /** Whether this object key requires a valid signature to serve. */
    public boolean protects(String s3Key) {
        if (!enabled() || s3Key == null) return false;
        for (String prefix : props.getServing().getProtectedPrefixes()) {
            if (prefix != null && !prefix.isBlank() && s3Key.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Sign a proxy/CDN media URL with the default TTL. */
    public String sign(String url, String s3Key) {
        return sign(url, s3Key, props.getServing().getSignedUrlTtlSeconds());
    }

    /** Sign with an explicit TTL; pass-through when signing is not live. */
    public String sign(String url, String s3Key, long ttlSeconds) {
        if (!enabled() || url == null || s3Key == null) return url;
        long exp = Instant.now().getEpochSecond() + Math.max(1, ttlSeconds);
        String sig = hmac(s3Key + "|" + exp);
        return url + (url.contains("?") ? "&" : "?") + "exp=" + exp + "&sig=" + sig;
    }

    /**
     * Verify a serve request. Only meaningful for protected keys — the
     * controller calls {@link #protects} first.
     */
    public boolean verify(String s3Key, String exp, String sig) {
        if (!enabled() || s3Key == null || exp == null || sig == null) return false;
        long expSeconds;
        try {
            expSeconds = Long.parseLong(exp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.now().getEpochSecond() > expSeconds) return false;
        byte[] expected = hmac(s3Key + "|" + expSeconds).getBytes(StandardCharsets.US_ASCII);
        byte[] given = sig.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);   // constant-time compare
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    props.getServing().getSigningSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Config error (bad secret) — fail closed: signatures never verify.
            log.error("[MEDIA-SIGN] HMAC failure: {}", e.getMessage());
            return "invalid";
        }
    }
}
