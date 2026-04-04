package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtCookieUtil;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.dto.request.AuthRequests;
import ak.dev.irc.app.user.dto.response.AuthResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.RefreshToken;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Handles registration, login, token refresh, and logout.
 *
 * <h3>Token delivery</h3>
 * Tokens are delivered via <b>two channels simultaneously</b>:
 * <ul>
 *   <li><b>HttpOnly cookies</b> — for browser clients (auto-sent on every request)</li>
 *   <li><b>JSON response body</b> — for mobile/API clients using Authorization header</li>
 * </ul>
 *
 * <h3>Refresh token rotation</h3>
 * Every refresh rotates: old token is revoked, new pair is issued.
 * If a revoked token is reused, ALL sessions are terminated (security measure).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager   authenticationManager;
    private final JwtTokenProvider        jwtTokenProvider;
    private final JwtCookieUtil           jwtCookieUtil;
    private final UserRepository          userRepository;
    private final UserFollowRepository    followRepository;
    private final RefreshTokenRepository  refreshTokenRepository;
    private final PasswordEncoder         passwordEncoder;
    private final UserMapper              userMapper;

    // ══════════════════════════════════════════════════════════════════════════
    //  REGISTER
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public AuthResponse register(AuthRequests.RegisterRequest request,
                                  HttpServletResponse response) {
        log.info("Registration attempt — email='{}', username='{}'",
                request.email(), request.username());

        if (userRepository.existsByEmail(request.email())) {
            log.warn("Registration rejected — duplicate email '{}'", request.email());
            throw new DuplicateResourceException("User", "email", request.email());
        }
        if (userRepository.existsByUsername(request.username())) {
            log.warn("Registration rejected — duplicate username '{}'", request.username());
            throw new DuplicateResourceException("User", "username", request.username());
        }

        User user = User.builder()
                .fname(request.fname())
                .lname(request.lname())
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .isEnabled(true)
                .build();
        user.audit(AuditAction.CREATE, "User registered");
        user = userRepository.save(user);

        log.info("User registered — id={}, email='{}'", user.getId(), user.getEmail());

        return issueTokenPair(user, response);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOGIN
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public AuthResponse login(AuthRequests.LoginRequest request,
                              HttpServletResponse response) {
        log.info("Login attempt — username/email='{}'", request.username());

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(),   // ← was request.email()
                        request.password()));

        User user = (User) authentication.getPrincipal();

        userRepository.updateLastLogin(user.getId());
        user.audit(AuditAction.LOGIN, "User logged in");
        userRepository.save(user);

        log.info("Login successful — id={}, email='{}'", user.getId(), user.getEmail());

        return issueTokenPair(user, response);
    }
    // ══════════════════════════════════════════════════════════════════════════
    //  REFRESH TOKEN
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public AuthResponse refreshToken(AuthRequests.RefreshTokenRequest request,
                                      HttpServletRequest httpRequest,
                                      HttpServletResponse httpResponse) {
        log.debug("Token refresh attempt");

        // ── Resolve refresh token: body first, then cookie ──
        String rawToken = request != null && request.refreshToken() != null
                ? request.refreshToken()
                : jwtCookieUtil.getRefreshTokenFromCookie(httpRequest).orElse(null);

        if (rawToken == null || rawToken.isBlank()) {
            log.warn("Token refresh rejected — no refresh token provided");
            throw new UnauthorizedException(
                    "No refresh token provided. Include it in the request body or as a cookie.",
                    "AUTH_REFRESH_TOKEN_MISSING");
        }

        // ── Validate JWT structure ──
        if (!jwtTokenProvider.validateToken(rawToken)) {
            log.warn("Token refresh rejected — JWT validation failed");
            throw new UnauthorizedException(
                    "Refresh token is invalid or has expired. Please log in again.",
                    "AUTH_REFRESH_TOKEN_INVALID");
        }

        // ── Ensure it's a REFRESH token ──
        String tokenType = jwtTokenProvider.getTokenType(rawToken);
        if (!"REFRESH".equals(tokenType)) {
            log.warn("Token refresh rejected — wrong token type '{}'", tokenType);
            throw new UnauthorizedException(
                    "The provided token is not a refresh token.",
                    "AUTH_WRONG_TOKEN_TYPE",
                    Map.of("providedType", String.valueOf(tokenType), "expectedType", "REFRESH"));
        }

        // ── Look up persisted token ──
        RefreshToken storedToken = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> {
                    log.warn("Token refresh rejected — not found in database");
                    return new UnauthorizedException(
                            "Refresh token not recognised. It may have been revoked.",
                            "AUTH_REFRESH_TOKEN_NOT_FOUND");
                });

        // ── Reuse detection ──
        if (storedToken.isRevoked()) {
            log.error("SECURITY: Revoked refresh token reuse detected for user [{}]. " +
                      "Revoking ALL sessions.", storedToken.getUser().getId());
            refreshTokenRepository.revokeAllForUser(storedToken.getUser().getId());
            jwtCookieUtil.clearAllTokenCookies(httpResponse);
            throw new UnauthorizedException(
                    "This refresh token has been revoked. All sessions terminated for security. " +
                    "Please log in again.",
                    "AUTH_REFRESH_TOKEN_REUSED");
        }

        if (storedToken.isExpired()) {
            log.warn("Token refresh rejected — stored token expired for user [{}]",
                    storedToken.getUser().getId());
            throw new UnauthorizedException(
                    "Refresh token has expired. Please log in again.",
                    "AUTH_REFRESH_TOKEN_EXPIRED");
        }

        // ── Rotate: revoke old, issue new pair ──
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();
        user.audit(AuditAction.TOKEN_REFRESH, "Token refreshed");
        userRepository.save(user);

        log.info("Token refreshed for user [{}]", user.getId());

        String newAccess  = jwtTokenProvider.generateAccessToken(user);
        String newRefresh = jwtTokenProvider.generateRefreshToken(user);
        persistRefreshToken(user, newRefresh);

        // Set cookies
        jwtCookieUtil.addAccessTokenCookie(httpResponse, newAccess);
        jwtCookieUtil.addRefreshTokenCookie(httpResponse, newRefresh);

        return AuthResponse.ofRefresh(
                newAccess, newRefresh, jwtTokenProvider.getAccessTokenExpirationMs());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOGOUT
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void logout(AuthRequests.LogoutRequest request,
                       HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse) {

        // Resolve refresh token from body or cookie
        String refreshToken = request != null && request.refreshToken() != null
                ? request.refreshToken()
                : jwtCookieUtil.getRefreshTokenFromCookie(httpRequest).orElse(null);

        if (refreshToken != null && !refreshToken.isBlank()) {
            log.info("Logout — revoking refresh token");
            refreshTokenRepository.revokeByToken(refreshToken);
        }

        // Clear cookies
        jwtCookieUtil.clearAllTokenCookies(httpResponse);

        UUID userId = SecurityUtils.getCurrentUserId().orElse(null);
        log.info("Logout completed for user [{}]", userId);
    }

    @Override
    public void logoutAll(HttpServletResponse httpResponse) {
        UUID userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

        log.info("Logout-all — revoking ALL refresh tokens for user [{}]", userId);
        refreshTokenRepository.revokeAllForUser(userId);
        jwtCookieUtil.clearAllTokenCookies(httpResponse);

        log.info("All sessions terminated for user [{}]", userId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private AuthResponse issueTokenPair(User user, HttpServletResponse response) {
        String accessToken  = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        persistRefreshToken(user, refreshToken);

        // Set cookies for browser clients
        jwtCookieUtil.addAccessTokenCookie(response, accessToken);
        jwtCookieUtil.addRefreshTokenCookie(response, refreshToken);

        long followers = followRepository.countByFollowingId(user.getId());
        long following = followRepository.countByFollowerId(user.getId());
        UserResponse userResponse = userMapper.toResponse(user, followers, following);

        log.debug("Token pair issued (body + cookies) for user [{}]", user.getId());

        return AuthResponse.ofTokens(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs(),
                userResponse
        );
    }

    private void persistRefreshToken(User user, String rawToken) {
        long refreshMs = jwtTokenProvider.getRefreshTokenExpirationMs();

        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .token(rawToken)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshMs / 1000))
                .build();
        entity.audit(AuditAction.CREATE, "Refresh token created");
        refreshTokenRepository.save(entity);

        log.debug("Refresh token persisted for user [{}], expires at {}",
                user.getId(), entity.getExpiresAt());
    }
}
