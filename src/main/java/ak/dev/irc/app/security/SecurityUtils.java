package ak.dev.irc.app.security;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.user.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

public class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<UUID> getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof User user) {
            return Optional.of(user.getId());
        }
        return Optional.empty();
    }

    /**
     * Same as {@link #getCurrentUserId()} but throws {@link UnauthorizedException}
     * (→ HTTP 401) when no authenticated user is present, instead of the bare
     * {@link java.util.NoSuchElementException} you get from {@code Optional.orElseThrow()}.
     *
     * <p>Use this in controllers behind {@code @PreAuthorize("isAuthenticated()")}.
     * That annotation usually rejects anonymous callers, but for SSE endpoints
     * an EventSource client without {@code withCredentials} won't send the auth
     * cookie — the request can reach the handler with no usable principal, and
     * a bare {@code orElseThrow()} surfaces as a 500.</p>
     */
    public static UUID requireCurrentUserId() {
        return getCurrentUserId().orElseThrow(() -> new UnauthorizedException(
                "Authentication is required for this endpoint.",
                "AUTH_REQUIRED"));
    }

    public static Optional<User> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof User user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }
}
