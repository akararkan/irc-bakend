package ak.dev.irc.app.security;

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
