package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.AuthRequests;
import ak.dev.irc.app.user.dto.response.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    AuthResponse register(AuthRequests.RegisterRequest request, HttpServletResponse response);

    AuthResponse login(AuthRequests.LoginRequest request, HttpServletResponse response);

    /**
     * Completes a login that stopped at the second factor. Accepts either the
     * authenticator's TOTP code or a single-use recovery code.
     */
    AuthResponse loginTwoFactor(AuthRequests.TwoFactorLoginRequest request,
                                HttpServletResponse response);

    AuthResponse refreshToken(AuthRequests.RefreshTokenRequest request,
                               HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse);

    void logout(AuthRequests.LogoutRequest request,
                HttpServletRequest httpRequest,
                HttpServletResponse httpResponse);

    void logoutAll(HttpServletResponse httpResponse);

    /**
     * Authenticated change-password. Re-verifies the current password,
     * updates the hash, then revokes every other session and rotates the
     * caller's tokens — so the user stays logged in on this device while
     * any other device that knew the old password is forced to re-login.
     */
    AuthResponse changePassword(AuthRequests.ChangePasswordRequest request,
                                 HttpServletResponse response);
}
