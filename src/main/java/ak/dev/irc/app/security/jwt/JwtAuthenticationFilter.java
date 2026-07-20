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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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

    // ── Skip only the PUBLIC auth routes ─────────────────────────────────────
    // Not the whole /api/v1/auth/** prefix: /logout, /logout-all and
    // /change-password carry @PreAuthorize("isAuthenticated()"), so their
    // Bearer tokens MUST be processed — a blanket skip left the
    // SecurityContext empty and 403'd every caller once method security
    // was enforced.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/refresh");
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

        // SSE endpoints carry their own auth via `?token=<jwt>` query param
        // because browser EventSource cannot send custom headers. If the
        // cookie we extracted is invalid (typically a stale cookie that has
        // expired since the user opened the tab), DON'T 401 the request —
        // pass through with no SecurityContext and let the controller's
        // `?token=` fallback handle auth. Otherwise the browser sees a 401
        // and surfaces it as a misleading "CORS / status null" error.
        boolean isSseEndpoint = request.getRequestURI().endsWith("/stream");

        try {
            // ── 2. Validate JWT ──
            if (!jwtTokenProvider.validateToken(jwt)) {
                if (isSseEndpoint) {
                    log.debug("Invalid JWT on SSE {} {} — passing through for ?token= fallback",
                            request.getMethod(), request.getRequestURI());
                    filterChain.doFilter(request, response);
                    return;
                }
                log.warn("Invalid JWT on {} {}", request.getMethod(), request.getRequestURI());
                writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                        "Invalid or expired JWT token. Please log in again.",
                        "AUTH_TOKEN_INVALID");
                return;
            }

            // ── 3. Ensure it's an ACCESS token ──
            String tokenType = jwtTokenProvider.getTokenType(jwt);
            if (!"ACCESS".equals(tokenType)) {
                if (isSseEndpoint) {
                    log.debug("Non-access token on SSE {} {} — passing through for ?token= fallback",
                            request.getMethod(), request.getRequestURI());
                    filterChain.doFilter(request, response);
                    return;
                }
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
                    if (isSseEndpoint) {
                        log.debug("Disabled user [{}] on SSE {} {} — passing through",
                                userId, request.getMethod(), request.getRequestURI());
                        filterChain.doFilter(request, response);
                        return;
                    }
                    log.warn("Disabled user [{}] attempted access on {} {}",
                            userId, request.getMethod(), request.getRequestURI());
                    writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                            "Your account is disabled. Please contact support.",
                            "AUTH_ACCOUNT_DISABLED");
                    return;
                }

                if (!userDetails.isAccountNonLocked()) {
                    if (isSseEndpoint) {
                        log.debug("Locked user [{}] on SSE {} {} — passing through",
                                userId, request.getMethod(), request.getRequestURI());
                        filterChain.doFilter(request, response);
                        return;
                    }
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
            // Expected JWT failures: malformed, expired, unsupported, bad signature.
            // SSE: don't 401 — let the controller's ?token= fallback try.
            if (isSseEndpoint) {
                log.debug("JWT error on SSE {} {} — passing through for ?token= fallback ({})",
                        request.getMethod(), request.getRequestURI(), ex.getMessage());
                filterChain.doFilter(request, response);
                return;
            }
            log.warn("JWT error on {} {} — {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                    "Invalid or expired token. Please log in again.",
                    "AUTH_TOKEN_INVALID");

        } catch (UsernameNotFoundException ex) {
            // Token is well-formed and unexpired but the user it points to no
            // longer exists (typical after a DB reset or account purge). This
            // is an authentication failure, not a 500 — surface it as such.
            if (isSseEndpoint) {
                log.debug("JWT references unknown user on SSE {} {} — passing through for ?token= fallback",
                        request.getMethod(), request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }
            log.warn("JWT references unknown user on {} {} — {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            writeErrorResponse(response, request, HttpStatus.UNAUTHORIZED,
                    "Your session is no longer valid. Please log in again.",
                    "AUTH_USER_NOT_FOUND");

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