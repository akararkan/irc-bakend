package ak.dev.irc.app.security.jwt;

import ak.dev.irc.app.user.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central JWT utility — creates tokens, validates them, and extracts claims.
 * <p>
 * Reads all configuration from {@code jwt.*} properties in application.yml.
 * Supports both access tokens (short-lived) and refresh tokens (long-lived).
 * </p>
 */
@Slf4j
@Getter
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final String issuer;

    // ── Cookie configuration (exposed for CookieUtil and filter) ──
    private final String cookieName;
    private final boolean cookieSecure;
    private final boolean cookieHttpOnly;
    private final String cookieSameSite;
    private final String cookiePath;
    private final int cookieMaxAge;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String base64Secret,
            @Value("${jwt.expiration-ms:3600000}") long accessMs,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshMs,
            @Value("${jwt.issuer:irc-platform}") String issuer,
            @Value("${jwt.cookie-name:IRC_TOKEN}") String cookieName,
            @Value("${jwt.cookie-secure:false}") boolean cookieSecure,
            @Value("${jwt.cookie-http-only:true}") boolean cookieHttpOnly,
            @Value("${jwt.cookie-same-site:Strict}") String cookieSameSite,
            @Value("${jwt.cookie-path:/}") String cookiePath,
            @Value("${jwt.cookie-max-age:86400}") int cookieMaxAge) {

        byte[] keyBytes = Decoders.BASE64.decode(base64Secret);
        this.signingKey              = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpirationMs = accessMs;
        this.refreshTokenExpirationMs = refreshMs;
        this.issuer                   = issuer;
        this.cookieName               = cookieName;
        this.cookieSecure             = cookieSecure;
        this.cookieHttpOnly           = cookieHttpOnly;
        this.cookieSameSite           = cookieSameSite;
        this.cookiePath               = cookiePath;
        this.cookieMaxAge             = cookieMaxAge;

        log.info("JWT provider initialised — issuer='{}', accessTTL={}ms, refreshTTL={}ms, " +
                 "cookieName='{}', cookieSecure={}, cookieHttpOnly={}, cookieSameSite='{}'",
                issuer, accessMs, refreshMs, cookieName, cookieSecure, cookieHttpOnly, cookieSameSite);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TOKEN GENERATION
    // ══════════════════════════════════════════════════════════════════════════

    public String generateAccessToken(User user) {
        log.debug("Generating access token for user [{}] ({})", user.getId(), user.getEmail());

        Map<String, Object> claims = buildClaims(user, "ACCESS");

        return Jwts.builder()
                .claims(claims)
                .subject(user.getId().toString())
                .issuer(issuer)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpirationMs))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(User user) {
        log.debug("Generating refresh token for user [{}]", user.getId());

        return Jwts.builder()
                .claim("tokenType", "REFRESH")
                .subject(user.getId().toString())
                .issuer(issuer)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpirationMs))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TOKEN VALIDATION
    // ══════════════════════════════════════════════════════════════════════════

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT expired — subject='{}', expiredAt={}",
                    ex.getClaims().getSubject(), ex.getClaims().getExpiration());
        } catch (UnsupportedJwtException ex) {
            log.warn("Unsupported JWT — {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("Malformed JWT — {}", ex.getMessage());
        } catch (SignatureException ex) {
            log.warn("Invalid JWT signature — {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims string is empty — {}", ex.getMessage());
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CLAIM EXTRACTION
    // ══════════════════════════════════════════════════════════════════════════

    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String getEmailFromToken(String token) {
        return parseClaims(token).get("email", String.class);
    }

    public String getTokenType(String token) {
        return parseClaims(token).get("tokenType", String.class);
    }

    public Date getExpirationFromToken(String token) {
        return parseClaims(token).getExpiration();
    }

    @SuppressWarnings("unchecked")
    public List<String> getAuthoritiesFromToken(String token) {
        Object raw = parseClaims(token).get("authorities");
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return Collections.emptyList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERNAL
    // ══════════════════════════════════════════════════════════════════════════

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Map<String, Object> buildClaims(User user, String tokenType) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email",      user.getEmail());
        claims.put("username",   user.getUsername());
        claims.put("fullName",   user.getFullName());
        claims.put("role",       user.getRole().name());
        claims.put("tokenType",  tokenType);
        claims.put("authorities",
                user.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList()));
        return claims;
    }
}
