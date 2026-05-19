package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.FriendSuggestionEntity;
import ak.dev.irc.app.post.cassandra.repository.FriendSuggestionRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Friends-of-friends suggestion engine.
 *
 * Algorithm (collaborative filtering, mutual-follower scoring):
 *   1. Pull the user's direct follow set (from Postgres UserFollow).
 *   2. For each followed user, pull THEIR follow set.
 *   3. Tally how many of the user's direct follows also follow each
 *      second-degree candidate — that count is the candidate's "score".
 *   4. Drop candidates who: are the user themselves, are already followed
 *      by the user, or have zero overlap.
 *   5. Persist top-N (score DESC) to friend_suggestions_by_user.
 *
 * Read path is then a single Cassandra partition scan — sub-millisecond.
 *
 * Recompute strategy:
 *   • on user_follows mutation (debounced via @Async)
 *   • or via a scheduled batch (added later); not implemented here
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendSuggestionService {

    private static final int MAX_SUGGESTIONS_TO_STORE = 50;
    private static final int MIN_MUTUAL_COUNT         = 2;

    private final UserFollowRepository       userFollowRepo;
    private final FriendSuggestionRepository suggestionRepo;

    /** Fast read — already sorted by score DESC in the partition. */
    public List<FriendSuggestionEntity> topSuggestionsFor(UUID userId, int limit) {
        return suggestionRepo.topSuggestions(userId, limit);
    }

    /**
     * Recompute suggestions for a single user. Runs async — fire from
     * controllers on follow/unfollow events; never block the request.
     */
    @Async
    public void recomputeFor(UUID userId) {
        long start = System.currentTimeMillis();
        try {
            // Step 1: direct follow set
            Set<UUID> directFollows = new HashSet<>(userFollowRepo.findFollowingIds(userId));
            if (directFollows.isEmpty()) {
                suggestionRepo.clearForUser(userId);
                return;
            }

            // Step 2 + 3: tally mutual counts across the second-degree graph
            Map<UUID, Integer> mutualCounts = new HashMap<>();
            for (UUID friend : directFollows) {
                List<UUID> friendsOfFriend = userFollowRepo.findFollowingIds(friend);
                for (UUID candidate : friendsOfFriend) {
                    if (candidate.equals(userId)) continue;          // not yourself
                    if (directFollows.contains(candidate)) continue; // already followed
                    mutualCounts.merge(candidate, 1, Integer::sum);
                }
            }

            // Step 4 + 5: keep top N
            List<Map.Entry<UUID, Integer>> top = mutualCounts.entrySet().stream()
                    .filter(e -> e.getValue() >= MIN_MUTUAL_COUNT)
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(MAX_SUGGESTIONS_TO_STORE)
                    .collect(Collectors.toList());

            suggestionRepo.clearForUser(userId);
            Instant now = Instant.now();
            for (Map.Entry<UUID, Integer> e : top) {
                suggestionRepo.save(FriendSuggestionEntity.builder()
                        .userId(userId)
                        .score(e.getValue())
                        .candidateId(e.getKey())
                        .reason(e.getValue() + " mutual follows")
                        .computedAt(now)
                        .build());
            }
            log.info("[SUGGEST] user {} → {} suggestions in {} ms",
                    userId, top.size(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.warn("[SUGGEST] recompute for user {} failed: {}", userId, ex.getMessage());
        }
    }
}
