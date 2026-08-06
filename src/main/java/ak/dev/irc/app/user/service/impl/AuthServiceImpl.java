package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.AuthMessages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtCookieUtil;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.dto.request.AuthRequests;
import ak.dev.irc.app.user.dto.response.AuthResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.RefreshToken;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.mapper.UserMapper;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import ak.dev.irc.app.user.repository.UserProfileRepository;
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
    private final UserProfileRepository   profileRepository;
    private final RefreshTokenRepository  refreshTokenRepository;
    private final PasswordEncoder         passwordEncoder;
    private final UserMapper              userMapper;
    private final ak.dev.irc.app.user.search.service.UserSearchService userSearch;
    private final ak.dev.irc.app.user.service.UserProvisioningService provisioningService;
    private final ak.dev.irc.app.security.login.service.LoginEventService loginEventService;
    private final ak.dev.irc.app.security.session.SessionDenylist sessionDenylist;

    // ══════════════════════════════════════════════════════════════════════════
    //  REGISTER
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public AuthResponse register(AuthRequests.RegisterRequest request,
                                  HttpServletResponse response) {
        log.info("Registration attempt — email='{}', username='{}'",
                request.email(), request.username());

        // Shared provisioning path — identical mechanics for self-signup and
        // admin-create. Self-signup grants USER; RESEARCHER/SCHOLAR are
        // admin-elevated tiers (user-administration.md §2 recon fix).
        User user = provisioningService.provision(
                new ak.dev.irc.app.user.service.UserProvisioningService.ProvisionCommand(
                        request.fname(), request.lname(), request.username(), request.email(),
                        request.password(), ak.dev.irc.app.user.enums.Role.USER,
                        true, false, "User registered"));

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

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(),   // ← was request.email()
                            request.password()));
        } catch (org.springframework.security.core.AuthenticationException authEx) {
            recordLoginEvent(resolveUserId(request.username()), "PASSWORD", "FAILED");
            throw authEx;
        }

        User user = (User) authentication.getPrincipal();

        userRepository.updateLastLogin(user.getId());
        user.audit(AuditAction.LOGIN, "User logged in");
        userRepository.save(user);

        // login_events writer — was never wired anywhere, leaving the table
        // permanently empty. Success path also fires the new-IP alert.
        try {
            loginEventService.recordSuccessAndAlertIfNew(
                    user.getId(), currentIp(), currentUserAgent(), "PASSWORD");
        } catch (Exception e) {
            log.debug("login_events write failed (non-fatal): {}", e.getMessage());
        }

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
                    AuthMessages.AUTH_REFRESH_TOKEN_MISSING_MSG,
                    AuthMessages.AUTH_REFRESH_TOKEN_MISSING);
        }

        // ── Validate JWT structure ──
        if (!jwtTokenProvider.validateToken(rawToken)) {
            log.warn("Token refresh rejected — JWT validation failed");
            throw new UnauthorizedException(
                    AuthMessages.AUTH_REFRESH_TOKEN_INVALID_MSG,
                    AuthMessages.AUTH_REFRESH_TOKEN_INVALID);
        }

        // ── Ensure it's a REFRESH token ──
        String tokenType = jwtTokenProvider.getTokenType(rawToken);
        if (!"REFRESH".equals(tokenType)) {
            log.warn("Token refresh rejected — wrong token type '{}'", tokenType);
            throw new UnauthorizedException(
                    AuthMessages.AUTH_WRONG_TOKEN_TYPE_MSG,
                    AuthMessages.AUTH_WRONG_TOKEN_TYPE,
                    Map.of("providedType", String.valueOf(tokenType), "expectedType", "REFRESH"));
        }

        // ── Look up persisted token ──
        RefreshToken storedToken = refreshTokenRepository.findByToken(rawToken)
                .orElseThrow(() -> {
                    log.warn("Token refresh rejected — not found in database");
                    return new UnauthorizedException(
                            AuthMessages.AUTH_REFRESH_TOKEN_NOT_FOUND_MSG,
                            AuthMessages.AUTH_REFRESH_TOKEN_NOT_FOUND);
                });

        // ── Reuse detection ──
        if (storedToken.isRevoked()) {
            log.error("SECURITY: Revoked refresh token reuse detected for user [{}]. " +
                      "Revoking ALL sessions.", storedToken.getUser().getId());
            refreshTokenRepository.revokeAllForUser(storedToken.getUser().getId());
            jwtCookieUtil.clearAllTokenCookies(httpResponse);
            throw new UnauthorizedException(
                    AuthMessages.AUTH_REFRESH_TOKEN_REUSED_MSG,
                    AuthMessages.AUTH_REFRESH_TOKEN_REUSED);
        }

        if (storedToken.isExpired()) {
            log.warn("Token refresh rejected — stored token expired for user [{}]",
                    storedToken.getUser().getId());
            throw new UnauthorizedException(
                    AuthMessages.AUTH_REFRESH_TOKEN_EXPIRED_MSG,
                    AuthMessages.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        // ── Rotate: revoke old, issue new pair ──
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();
        user.audit(AuditAction.TOKEN_REFRESH, "Token refreshed");
        userRepository.save(user);

        log.info("Token refreshed for user [{}]", user.getId());

        // Session continuity: the rotated pair keeps the old row's sid so the
        // device stays one revocable session across rotations.
        UUID sid = storedToken.getSid() != null ? storedToken.getSid() : UUID.randomUUID();
        String newAccess  = jwtTokenProvider.generateAccessToken(user, sid);
        String newRefresh = jwtTokenProvider.generateRefreshToken(user);
        persistRefreshToken(user, newRefresh, sid);

        recordLoginEvent(user.getId(), "REFRESH", "SUCCESS");

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
            // Deny the session id too, so the paired access token dies with
            // the refresh token instead of surviving until expiry.
            refreshTokenRepository.findByToken(refreshToken)
                    .map(RefreshToken::getSid)
                    .ifPresent(sid -> sessionDenylist.deny(sid, null));
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
                .orElseThrow(() -> new UnauthorizedException(AuthMessages.AUTH_NOT_AUTHENTICATED_MSG));

        log.info("Logout-all — revoking ALL refresh tokens for user [{}]", userId);
        refreshTokenRepository.revokeAllForUser(userId);
        jwtCookieUtil.clearAllTokenCookies(httpResponse);

        log.info("All sessions terminated for user [{}]", userId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CHANGE PASSWORD  (authenticated user only — no reset-token flow)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public AuthResponse changePassword(AuthRequests.ChangePasswordRequest request,
                                        HttpServletResponse response) {
        UUID userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException(
                        AuthMessages.AUTH_REQUIRED_MSG, AuthMessages.AUTH_REQUIRED));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException(
                        AuthMessages.USER_NOT_FOUND_MSG, AuthMessages.USER_NOT_FOUND));

        // 1) Re-verify the CURRENT password — defends against session hijack.
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            log.warn("Change-password rejected — wrong current password for user [{}]", userId);
            throw new UnauthorizedException(
                    AuthMessages.AUTH_CURRENT_PASSWORD_INVALID_MSG,
                    AuthMessages.AUTH_CURRENT_PASSWORD_INVALID);
        }

        // 2) Block obvious no-op (encoded compare, not raw — avoids matching by chance).
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new ak.dev.irc.app.common.exception.BadRequestException(
                    AuthMessages.AUTH_NEW_PASSWORD_SAME_AS_CURRENT_MSG,
                    AuthMessages.AUTH_NEW_PASSWORD_SAME_AS_CURRENT);
        }

        // 3) Persist the new hash.
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.audit(AuditAction.UPDATE, "Password changed");
        userRepository.save(user);

        // 4) Revoke every existing refresh token, then issue a fresh pair to
        //    the caller — they keep their session, every OTHER device is
        //    forced to log in again with the new password.
        refreshTokenRepository.revokeAllForUser(userId);

        log.info("Password changed for user [{}] — all other sessions revoked", userId);

        return issueTokenPair(user, response);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private AuthResponse issueTokenPair(User user, HttpServletResponse response) {
        UUID sid = UUID.randomUUID();
        String accessToken  = jwtTokenProvider.generateAccessToken(user, sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        persistRefreshToken(user, refreshToken, sid);

        // Set cookies for browser clients
        jwtCookieUtil.addAccessTokenCookie(response, accessToken);
        jwtCookieUtil.addRefreshTokenCookie(response, refreshToken);

        UserResponse userResponse = userMapper.toResponse(user, true);

        log.debug("Token pair issued (body + cookies) for user [{}]", user.getId());

        return AuthResponse.ofTokens(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs(),
                userResponse
        );
    }

    private void persistRefreshToken(User user, String rawToken, UUID sid) {
        long refreshMs = jwtTokenProvider.getRefreshTokenExpirationMs();

        String userAgent = currentUserAgent();
        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .token(rawToken)
                .sid(sid)
                .ipAddress(currentIp())
                .userAgent(userAgent)
                .platform(platformOf(userAgent))
                .deviceName(deviceNameOf(userAgent))
                .lastSeenAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusSeconds(refreshMs / 1000))
                .build();
        entity.audit(AuditAction.CREATE, "Refresh token created");
        refreshTokenRepository.save(entity);

        log.debug("Refresh token persisted for user [{}], sid={}, expires at {}",
                user.getId(), sid, entity.getExpiresAt());
    }

    // ── login_events + session-metadata helpers ─────────────────────────────

    private void recordLoginEvent(UUID userId, String method, String outcome) {
        try {
            loginEventService.record(userId, currentIp(), currentUserAgent(), method, outcome);
        } catch (Exception e) {
            log.debug("login_events write failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Best-effort user resolution for FAILED login rows (nullable by design). */
    private UUID resolveUserId(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        try {
            return (identifier.contains("@")
                    ? userRepository.findByEmailAndDeletedAtIsNull(identifier)
                    : userRepository.findByUsernameAndDeletedAtIsNull(identifier))
                    .map(User::getId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String currentIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        ip = req.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip.trim();
        return req.getRemoteAddr();
    }

    private static String currentUserAgent() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String ua = req.getHeader("User-Agent");
        return ua != null && ua.length() > 400 ? ua.substring(0, 400) : ua;
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                return sra.getRequest();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String platformOf(String ua) {
        if (ua == null) return null;
        String lower = ua.toLowerCase();
        if (lower.contains("android")) return "android";
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) return "ios";
        if (lower.contains("windows") || lower.contains("macintosh") || lower.contains("linux")) return "desktop";
        return "web";
    }

    private static String deviceNameOf(String ua) {
        if (ua == null) return null;
        String browser = "Browser";
        String lower = ua.toLowerCase();
        if (lower.contains("edg/")) browser = "Edge";
        else if (lower.contains("chrome/")) browser = "Chrome";
        else if (lower.contains("safari/") && !lower.contains("chrome/")) browser = "Safari";
        else if (lower.contains("firefox/")) browser = "Firefox";
        String os = "Unknown OS";
        if (lower.contains("windows")) os = "Windows";
        else if (lower.contains("macintosh")) os = "macOS";
        else if (lower.contains("android")) os = "Android";
        else if (lower.contains("iphone") || lower.contains("ipad")) os = "iOS";
        else if (lower.contains("linux")) os = "Linux";
        String name = browser + " on " + os;
        return name.length() > 120 ? name.substring(0, 120) : name;
    }
}
