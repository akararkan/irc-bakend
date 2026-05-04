package ak.dev.irc.app.common.service;

import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hot-path cache for "the set of users this user can see in their following feed".
 *
 * <p>Following + block status changes infrequently compared to feed reads, so a
 * 1-minute Redis cache lets feed pagination scroll without hammering the DB
 * on every page. Cache is evicted from
 * {@link ak.dev.irc.app.user.service.impl.UserSocialServiceImpl} on follow,
 * unfollow, block, and unblock.</p>
 *
 * <p>The result is already block-filtered so feed services can pass it
 * straight into a {@code IN :authorIds} clause.</p>
 */
@Component
@RequiredArgsConstructor
public class FollowingIdsCache {

    private final UserFollowRepository followRepo;
    private final UserBlockRepository blockRepo;

    @Cacheable(value = "user-following-ids", key = "#userId", unless = "#userId == null")
    @Transactional(readOnly = true)
    public List<UUID> getFilteredFollowingIds(UUID userId) {
        if (userId == null) return List.of();

        // Important: return a fresh ArrayList so cache copies stay mutable on
        // hot reload by callers (some Spring cache configs return immutable lists).
        List<UUID> followingIds = new ArrayList<>(followRepo.findFollowingIds(userId));
        if (followingIds.isEmpty()) return followingIds;

        Set<UUID> blocked = new HashSet<>(blockRepo.findBlockedAmong(userId, followingIds));
        if (blocked.isEmpty()) return followingIds;

        followingIds.removeIf(blocked::contains);
        return followingIds;
    }
}
