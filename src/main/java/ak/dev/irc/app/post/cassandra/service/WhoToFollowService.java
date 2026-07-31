package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.FriendSuggestionEntity;
import ak.dev.irc.app.post.cassandra.repository.FriendSuggestionRepository;
import ak.dev.irc.app.user.dto.response.FollowSuggestionResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserProfileRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Two "who to follow" surfaces:
 *
 *   1. friendSuggestions  — friends-of-friends, scored by mutual-follow count.
 *      Source: precomputed {@code friend_suggestions_by_user} Cassandra partition.
 *      Hydrated with Postgres user + profile data in two bulk queries.
 *
 *   2. whoToFollow         — verified / popular accounts the viewer doesn't yet follow.
 *      Source: Postgres native query that orders by account type then follower count,
 *      using subqueries to exclude already-followed and blocked users — no empty-list
 *      parameter risk.
 *
 * Both endpoints return {@link FollowSuggestionResponse} so the frontend renders
 * a suggestion card without any follow-up API calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhoToFollowService {

    private final FriendSuggestionRepository suggestionRepo;
    private final UserRepository             userRepo;
    private final UserProfileRepository      profileRepo;
    private final UserFollowRepository       followRepo;
    private final ak.dev.irc.app.user.repository.SuggestionDismissalRepository dismissalRepo;

    // ── Friends-of-friends ───────────────────────────────────────────────────

    /**
     * Returns the viewer's precomputed friend suggestions, hydrated with full
     * user + profile data. Falls back to {@link #whoToFollow} if the
     * suggestion partition is empty (new user, no mutual follows yet).
     */
    @Transactional(readOnly = true)
    public List<FollowSuggestionResponse> friendSuggestions(UUID viewerId, int limit) {
        List<FriendSuggestionEntity> raw = suggestionRepo.topSuggestions(viewerId, limit);
        if (raw.isEmpty()) {
            return whoToFollow(viewerId, limit);
        }

        Set<UUID> candidateIds = raw.stream()
                .map(FriendSuggestionEntity::getCandidateId)
                .collect(Collectors.toSet());

        Map<UUID, User>        users    = bulkUsers(candidateIds);
        Map<UUID, UserProfile> profiles = bulkProfiles(candidateIds);
        Set<UUID>              following = Set.copyOf(followRepo.findFollowingIds(viewerId));

        List<FollowSuggestionResponse> out = new ArrayList<>(raw.size());
        for (FriendSuggestionEntity s : raw) {
            User        u = users.get(s.getCandidateId());
            UserProfile p = profiles.get(s.getCandidateId());
            if (u == null) continue;
            out.add(new FollowSuggestionResponse(
                    u.getId(),
                    u.getUsername(),
                    displayName(u, p),
                    avatarUrl(p),
                    followerCount(p),
                    u.getRole(),
                    following.contains(u.getId()),
                    s.getScore() == null ? 0 : s.getScore(),
                    s.getReason() != null ? s.getReason() : "Suggested for you"
            ));
        }
        return out;
    }

    // ── Who to follow (verified / popular) ───────────────────────────────────

    /**
     * Returns popular accounts the viewer does not yet follow.
     * Ordered: SCHOLAR → RESEARCHER → USER, then by follower count DESC.
     * Block-filtered and self-excluded via SQL subqueries.
     */
    @Transactional(readOnly = true)
    public List<FollowSuggestionResponse> whoToFollow(UUID viewerId, int limit) {
        List<User> candidates = viewerId != null
                ? userRepo.findWhoToFollow(viewerId, limit)
                : userRepo.findWhoToFollowAnon(limit);

        if (candidates.isEmpty()) return List.of();

        Set<UUID>              ids      = candidates.stream().map(User::getId).collect(Collectors.toSet());
        Map<UUID, UserProfile> profiles = bulkProfiles(ids);
        Set<UUID>              following = viewerId != null
                ? Set.copyOf(followRepo.findFollowingIds(viewerId))
                : Set.of();

        return candidates.stream()
                .map(u -> {
                    UserProfile p = profiles.get(u.getId());
                    return new FollowSuggestionResponse(
                            u.getId(),
                            u.getUsername(),
                            displayName(u, p),
                            avatarUrl(p),
                            followerCount(p),
                            u.getRole(),
                            following.contains(u.getId()),
                            0,
                            reason(u)
                    );
                })
                .collect(Collectors.toList());
    }

    // ── Dismiss ──────────────────────────────────────────────────────────────

    /**
     * Remove a candidate from the viewer's suggestion partition. Cassandra
     * requires the FULL primary key (incl. the score clustering column) for
     * the delete — the old two-arg call failed at runtime — so read the
     * row(s) first to learn the score.
     */
    public void dismiss(UUID viewerId, UUID candidateId) {
        // Persist the negative signal so the candidate stays gone after the
        // next recompute — not just until it.
        try {
            dismissalRepo.save(ak.dev.irc.app.user.entity.SuggestionDismissal.builder()
                    .id(new ak.dev.irc.app.user.entity.SuggestionDismissal.Key(viewerId, candidateId))
                    .dismissedAt(java.time.Instant.now())
                    .build());
        } catch (Exception e) {
            log.debug("[WHO-TO-FOLLOW] dismissal persist skipped: {}", e.getMessage());
        }
        for (FriendSuggestionEntity row : suggestionRepo.topSuggestions(viewerId, 100)) {
            if (candidateId.equals(row.getCandidateId()) && row.getScore() != null) {
                suggestionRepo.deleteSuggestion(viewerId, row.getScore(), candidateId);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<UUID, User> bulkUsers(Set<UUID> ids) {
        return userRepo.findActiveByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
    }

    private Map<UUID, UserProfile> bulkProfiles(Set<UUID> ids) {
        return profileRepo.findAllByUserIdIn(ids).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p));
    }

    private static String displayName(User u, UserProfile p) {
        if (p != null && p.getDisplayName() != null && !p.getDisplayName().isBlank())
            return p.getDisplayName();
        return (u.getFname() + " " + u.getLname()).trim();
    }

    private static String avatarUrl(UserProfile p) {
        return p != null ? p.getAvatarUrl() : null;
    }

    private static long followerCount(UserProfile p) {
        return p != null ? p.getFollowerCount() : 0L;
    }

    private static String reason(User u) {
        return switch (u.getRole()) {
            case SCHOLAR    -> "Verified Scholar";
            case RESEARCHER -> "Verified Researcher";
            default         -> "Suggested for you";
        };
    }
}
