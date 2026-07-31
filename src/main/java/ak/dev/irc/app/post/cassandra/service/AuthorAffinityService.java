package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.UserAuthorAffinityEntity;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.UserAuthorAffinityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Interest-graph accumulator: every positive engagement bumps the
 * (viewer → author) counter in {@code user_author_affinity}, weighted by
 * action strength at write time so the feed ranker reads ONE number per
 * author pair.
 *
 * <p>Weights follow the engagement-prediction hierarchy used by the big
 * feed systems (share/comment &gt; save &gt; like &gt; view):</p>
 * <pre>
 *   view +1 · like +3 · save +4 · comment +5 · share +5
 * </pre>
 *
 * <p>All writes are async on the bounded {@code taskExecutor} and
 * best-effort — an affinity miss never fails the user action. The post
 * author lookup (needed because engagement endpoints only carry postId)
 * also runs off the request thread.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorAffinityService {

    public static final long WEIGHT_VIEW     = 1L;
    public static final long WEIGHT_REACTION = 3L;
    public static final long WEIGHT_SAVE     = 4L;
    public static final long WEIGHT_COMMENT  = 5L;
    public static final long WEIGHT_SHARE    = 5L;

    private final CqlOperations                cqlOperations;
    private final PostByIdRepository           postByIdRepo;
    private final UserAuthorAffinityRepository affinityRepo;
    private final ThreadPoolTaskExecutor       taskExecutor;

    /**
     * Record an engagement on a post: resolves the post's author off-thread
     * and bumps the (viewer → author) counter. Self-engagement is skipped —
     * liking your own post is not interest signal.
     */
    public void recordForPost(UUID viewerId, UUID postId, long weight) {
        if (viewerId == null || postId == null || weight <= 0) return;
        try {
            taskExecutor.execute(() -> {
                try {
                    postByIdRepo.findById(postId).ifPresent(p -> {
                        if (p.getAuthorId() != null && !viewerId.equals(p.getAuthorId())) {
                            record(viewerId, p.getAuthorId(), weight);
                        }
                    });
                } catch (Exception e) {
                    log.debug("[AFFINITY] record for post {} skipped: {}", postId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("[AFFINITY] executor rejected affinity write: {}", e.getMessage());
        }
    }

    /** Direct counter bump when the author is already known. */
    public void record(UUID viewerId, UUID authorId, long weight) {
        if (viewerId == null || authorId == null || viewerId.equals(authorId) || weight <= 0) return;
        cqlOperations.execute(
                "UPDATE user_author_affinity SET interactions = interactions + ? " +
                "WHERE user_id = ? AND author_id = ?",
                weight, viewerId, authorId);
    }

    /**
     * Affinity scores for one viewer against a page of authors — one
     * single-partition round-trip. Missing pairs simply don't appear
     * (treat as 0). Fail-open: an empty map on error, never an exception
     * into the feed read path.
     */
    public Map<UUID, Long> bulkFor(UUID viewerId, Collection<UUID> authorIds) {
        if (viewerId == null || authorIds == null || authorIds.isEmpty()) return Map.of();
        try {
            Map<UUID, Long> out = new HashMap<>(authorIds.size());
            for (UserAuthorAffinityEntity row : affinityRepo.findFor(viewerId, authorIds)) {
                if (row.getAuthorId() != null && row.getInteractions() != null) {
                    out.put(row.getAuthorId(), row.getInteractions());
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("[AFFINITY] bulk load failed for viewer {}: {}", viewerId, e.getMessage());
            return Map.of();
        }
    }
}
