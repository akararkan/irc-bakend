package ak.dev.irc.app.common.service;

import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Backs the {@code @-mention} picker UI — given a fragment the user has
 * typed, returns a ranked list of candidate users suitable for inline
 * autocomplete.
 *
 * <p>Block-aware: any user the viewer has a block relationship with is
 * filtered out so the typer can't accidentally @-mention someone who
 * already cut them off (and so blockers never see their target in the
 * picker).</p>
 *
 * <p>Backed by the trigram + prefix indexes installed on the {@code users}
 * table — sub-5ms warm even on millions of users. Results are cached for
 * 30 seconds keyed by {@code (q, viewerId)} so a fast typer doesn't
 * generate a per-keystroke storm.</p>
 */
@Service
@RequiredArgsConstructor
public class MentionSuggestionService {

    /** Hard cap on suggestions per request. */
    private static final int MAX_LIMIT = 25;

    private final UserRepository userRepo;
    private final SocialGuard    socialGuard;

    @Transactional(readOnly = true)
    @Cacheable(value = "mention-suggestions",
               key = "T(java.util.Objects).hash(#q, #limit, #viewerId)",
               unless = "#q == null || #q.isBlank()")
    public List<Suggestion> suggest(String q, int limit, UUID viewerId) {
        if (q == null) return List.of();
        String normalized = q.startsWith("@") ? q.substring(1) : q;
        normalized = normalized.trim();
        if (normalized.length() < 1) return List.of();

        int safe = Math.max(1, Math.min(limit, MAX_LIMIT));

        // Over-fetch by 50% so we can drop blocked users without producing
        // a short result set on a single-block edge case.
        List<Object[]> rows = userRepo.findMentionCandidates(normalized, safe + safe / 2);
        if (rows.isEmpty()) return List.of();

        Map<UUID, Double> scoreById = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            Number score = (Number) row[1];
            scoreById.put(id, score == null ? 0.0 : score.doubleValue());
        }

        // Block filter — single round-trip via SocialGuard's cached helper.
        Set<UUID> blocked = viewerId == null
                ? Set.of()
                : Set.copyOf(socialGuard.findRelatedBlockedIds(viewerId));

        List<User> users = userRepo.findAllById(scoreById.keySet());

        return users.stream()
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> !u.isProfileLocked() || (viewerId != null && viewerId.equals(u.getId())))
                .filter(u -> !blocked.contains(u.getId()))
                .filter(u -> viewerId == null || !u.getId().equals(viewerId))
                .sorted(Comparator
                        .comparingDouble((User u) -> -scoreById.getOrDefault(u.getId(), 0.0))
                        .thenComparing((User u) -> u.getUsername() == null ? Integer.MAX_VALUE : u.getUsername().length()))
                .limit(safe)
                .map(Suggestion::from)
                .collect(Collectors.toList());
    }

    /** Compact payload for a mention picker — avoids streaming the full User entity. */
    @Data
    @Builder
    public static class Suggestion {
        private UUID id;
        private String username;
        private String fullName;
        private String avatarUrl;
        private String role;

        public static Suggestion from(User u) {
            return Suggestion.builder()
                    .id(u.getId())
                    .username(u.getUsername())
                    .fullName(u.getFullName())
                    .avatarUrl(u.getProfileImage())
                    .role(u.getRole() != null ? u.getRole().name() : null)
                    .build();
        }
    }
}
