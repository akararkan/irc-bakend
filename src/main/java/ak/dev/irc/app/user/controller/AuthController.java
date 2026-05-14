package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.AuthRequests;
import ak.dev.irc.app.user.dto.response.AuthResponse;
import ak.dev.irc.app.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints.
 *
 * <p>Tokens are delivered via <b>two channels simultaneously</b>:
 * <ul>
 *   <li><b>HttpOnly cookies</b> — for browser clients</li>
 *   <li><b>JSON response body</b> — for mobile/API clients</li>
 * </ul>
 *
 * <pre>
 *   POST /api/v1/auth/register          → register a new account
 *   POST /api/v1/auth/login             → authenticate and receive tokens
 *   POST /api/v1/auth/refresh           → exchange refresh token for new pair
 *   POST /api/v1/auth/logout            → revoke refresh token + clear cookies
 *   POST /api/v1/auth/logout-all        → revoke ALL refresh tokens + clear cookies
 *   POST /api/v1/auth/change-password   → authed-user-only password rotation
 * </pre>
 *
 * <p>There is intentionally <b>no</b> forgot-password / reset-token flow.
 * The only way to rotate a password is the {@code /change-password} endpoint
 * which requires an authenticated session and re-verifies the current password.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody AuthRequests.RegisterRequest request,
            HttpServletResponse response) {

        log.info("POST /api/v1/auth/register — email='{}'", request.email());
        AuthResponse authResponse = authService.register(request, response);
        return ResponseEntity.status(201).body(authResponse);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody AuthRequests.LoginRequest request,
            HttpServletResponse response) {

        log.info("POST /api/v1/auth/login — username='{}'", request.username());
        return ResponseEntity.ok(authService.login(request, response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(
            @RequestBody(required = false) AuthRequests.RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        log.debug("POST /api/v1/auth/refresh");
        return ResponseEntity.ok(
                authService.refreshToken(request, httpRequest, httpResponse));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) AuthRequests.LogoutRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        log.info("POST /api/v1/auth/logout");
        authService.logout(
                request != null ? request : new AuthRequests.LogoutRequest(null),
                httpRequest, httpResponse);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logoutAll(HttpServletResponse httpResponse) {

        log.info("POST /api/v1/auth/logout-all");
        authService.logoutAll(httpResponse);
        return ResponseEntity.ok().build();
    }

    /**
     * Authed-user password rotation.
     *
     * <p>The caller must already be logged in and must re-supply their current
     * password. On success: every refresh token for this user is revoked
     * (including the caller's old one), then a fresh access/refresh pair is
     * issued in the response body and HttpOnly cookies. The caller stays
     * logged in on this device; every other device is forced to log in again
     * with the new password.</p>
     *
     * <p>There is no /forgot-password endpoint by design — this is the only
     * path to change a password.</p>
     */
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuthResponse> changePassword(
            @Valid @RequestBody AuthRequests.ChangePasswordRequest request,
            HttpServletResponse response) {

        log.info("POST /api/v1/auth/change-password");
        return ResponseEntity.ok(authService.changePassword(request, response));
    }
}
