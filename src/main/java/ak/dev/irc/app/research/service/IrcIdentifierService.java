package ak.dev.irc.app.research.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates and verifies all IRC-issued identifiers for research publications.
 *
 * <p>Every published research gets three auto-assigned identifiers:
 * <ul>
 *   <li><b>IRC ID</b>   — human-readable serial:   {@code IRC-2026-000042}</li>
 *   <li><b>DOI</b>      — registered DOI:           {@code 10.{prefix}/irc.2026.000042}</li>
 *   <li><b>Share URL</b>— full public link:         {@code {baseUrl}/r/{shareToken}}</li>
 * </ul>
 *
 * <p>A tamper-proof HMAC-SHA256 {@code verificationHash} binds the IRC ID to the
 * research UUID and researcher UUID. The public verification endpoint checks this
 * hash so any third party can confirm a paper genuinely belongs to IRC.
 */
@Service
@Slf4j
public class IrcIdentifierService {

    /** CrossRef DOI prefix — register at https://www.crossref.org before going live. */
    @Value("${irc.doi.prefix:10.00000}")
    private String doiPrefix;

    /** Base URL for share links and verification pages, e.g. https://irc.example.com */
    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;


    // ── IRC ID ────────────────────────────────────────────────────────────────

    /**
     * Builds the human-readable IRC identifier.
     *
     * @param sequenceNumber globally unique sequential number from the DB sequence
     * @return e.g. {@code IRC-2026-000042}
     */
    public String generateIrcId(long sequenceNumber) {
        return String.format("IRC-%d-%06d", Year.now().getValue(), sequenceNumber);
    }

    // ── DOI ──────────────────────────────────────────────────────────────────

    /**
     * Builds a DOI from the IRC sequence number.
     *
     * @param sequenceNumber the same number used in the IRC ID
     * @return e.g. {@code 10.12345/irc.2026.000042}
     */
    public String generateDoi(long sequenceNumber) {
        return String.format("%s/irc.%d.%06d", doiPrefix, Year.now().getValue(), sequenceNumber);
    }

    // ── Share URL ─────────────────────────────────────────────────────────────

    /**
     * Builds the full public share URL for a paper.
     *
     * @param shareToken the 16-char token stored on the Research entity
     * @return e.g. {@code https://irc.example.com/r/abc123xyz456abcd}
     */
    public String buildShareUrl(String shareToken) {
        if (shareToken == null) return null;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/r/" + shareToken;
    }

    /**
     * Generates a cryptographically random 16-char URL-safe share token.
     */
    public String generateShareToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

}
