package ak.dev.irc.app.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import ak.dev.irc.app.common.dto.ApiErrorResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER   = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider   jwtTokenProvider;
    private final JwtCookieUtil      jwtCookieUtil;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper       objectMapper;

    // ── Skip filter entirely for auth & OAuth2 routes ────────────────────────
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // ── 1. Resolve token: cookie first, then header ──
        String jwt = resolveToken(request);

        // No token → pass through (public endpoint or will fail at authZ layer)
        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // ── 2. Validate JWT ──
            if (!jwtTokenProvider.validateToken(jwt)) {
                log.warn("Invalid JWT on {} {}", request.getMethod(), request.getRequestURI());
                writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                        "Invalid or expired JWT token. Please log in again.",
                        "AUTH_TOKEN_INVALID");
                return;
            }

            // ── 3. Ensure it's an ACCESS token ──
            String tokenType = jwtTokenProvider.getTokenType(jwt);
            if (!"ACCESS".equals(tokenType)) {
                log.warn("Non-access token type '{}' used on {} {}",
                        tokenType, request.getMethod(), request.getRequestURI());
                writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                        "This token type cannot be used for API access. Use an access token.",
                        "AUTH_WRONG_TOKEN_TYPE");
                return;
            }

            // ── 4. Load user and set SecurityContext ──
            UUID   userId = jwtTokenProvider.getUserIdFromToken(jwt);
            String email  = jwtTokenProvider.getEmailFromToken(jwt);

            if (SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                if (!userDetails.isEnabled()) {
                    log.warn("Disabled user [{}] attempted access on {} {}",
                            userId, request.getMethod(), request.getRequestURI());
                    writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                            "Your account is disabled. Please contact support.",
                            "AUTH_ACCOUNT_DISABLED");
                    return;
                }

                if (!userDetails.isAccountNonLocked()) {
                    log.warn("Locked user [{}] attempted access on {} {}",
                            userId, request.getMethod(), request.getRequestURI());
                    writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                            "Your account is locked. Please contact support.",
                            "AUTH_ACCOUNT_LOCKED");
                    return;
                }

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("Authenticated user [{}] ({}) for {} {}",
                        userId, email, request.getMethod(), request.getRequestURI());
            }

            filterChain.doFilter(request, response);

        } catch (JwtException | IllegalArgumentException ex) {
            // Expected JWT failures: malformed, expired, unsupported, bad signature
            log.warn("JWT error on {} {} — {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                    "Invalid or expired token. Please log in again.",
                    "AUTH_TOKEN_INVALID");

        } catch (Exception ex) {
            // Truly unexpected: DB down, NPE, etc.
            log.error("Unexpected error in JWT filter on {} {} — {}: {}",
                    request.getMethod(), request.getRequestURI(),
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            writeErrorResponse(response, request, HttpStatus.INTERNAL_SERVER_ERROR,
                    "An internal error occurred. Please try again.",
                    "INTERNAL_ERROR");
        }
    }

    private String resolveToken(HttpServletRequest request) {
        // 1) Cookie
        String fromCookie = jwtCookieUtil.getAccessTokenFromCookie(request).orElse(null);
        if (fromCookie != null) {
            log.trace("JWT resolved from cookie for {} {}", request.getMethod(), request.getRequestURI());
            return fromCookie;
        }

        // 2) Bearer header
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            log.trace("JWT resolved from Authorization header for {} {}",
                    request.getMethod(), request.getRequestURI());
            return header.substring(BEARER_PREFIX.length());
        }

        return null;
    }

    private void writeErrorResponse(HttpServletResponse response,
                                    HttpServletRequest request,
                                    HttpStatus status,
                                    String message,
                                    String errorCode) throws IOException {

        ApiErrorResponse error = ApiErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .errorCode(errorCode)
                .traceId(UUID.randomUUID().toString())
                .build();

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}