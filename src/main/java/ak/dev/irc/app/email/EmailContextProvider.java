package ak.dev.irc.app.email;

import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Hot-path lookup of {@link UserEmailContext}.
 *
 * <p>A high-traffic post can fan out to thousands of notifications in seconds —
 * each would otherwise hit the DB to read the recipient's email + prefs. With
 * this provider:
 * <ul>
 *   <li>First request per user → one tight {@code SELECT} returning a six-column
 *       projection (no JPA proxies, no lazy collections).</li>
 *   <li>Every subsequent request inside the 60-second TTL → Redis cache hit.</li>
 *   <li>{@code PATCH /users/me/email-preferences} evicts the cache so muting
 *       takes effect immediately.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EmailContextProvider {

    private final UserRepository userRepo;

    /**
     * Returns the recipient's email context, or {@code null} when the user is
     * missing / soft-deleted. Cached for 60 seconds.
     */
    @Cacheable(value = "user-email-ctx", key = "#userId", unless = "#result == null")
    @Transactional(readOnly = true)
    public UserEmailContext get(UUID userId) {
        if (userId == null) return null;
        return userRepo.findEmailContextById(userId).orElse(null);
    }

    /** Force a refresh on the next read — called from the prefs PATCH endpoint. */
    @CacheEvict(value = "user-email-ctx", key = "#userId")
    public void evict(UUID userId) {
        // body intentionally empty — annotation does the work
    }
}
