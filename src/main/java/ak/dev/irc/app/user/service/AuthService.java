package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.AuthRequests;
import ak.dev.irc.app.user.dto.response.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    AuthResponse register(AuthRequests.RegisterRequest request, HttpServletResponse response);

    AuthResponse login(AuthRequests.LoginRequest request, HttpServletResponse response);

    AuthResponse refreshToken(AuthRequests.RefreshTokenRequest request,
                               HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse);

    void logout(AuthRequests.LogoutRequest request,
                HttpServletRequest httpRequest,
                HttpServletResponse httpResponse);

    void logoutAll(HttpServletResponse httpResponse);
}
