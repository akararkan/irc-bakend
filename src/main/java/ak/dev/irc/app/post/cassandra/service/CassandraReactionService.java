package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.CommentLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.CommentReactionEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReactionByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.ReactionByUserEntity;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.repository.CommentLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentReactionRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByUserRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Toggle-like reactions for both posts and comments, backed by Cassandra.
 *
 * Single-reaction-type rule (project convention "academic not entertainment"):
 * we don't track which emoji was used — presence of the row IS the like.
 *
 * Write path is dual-table per project schema:
 *   • reactions_by_post   ← "did user U react to post P?" (point read)
 *   • reactions_by_user   ← user's history feed (newest first)
 *   • post_counters       ← +1/-1 on the reaction_count counter
 *
 * Race correctness:
 *   The toggle path uses Cassandra LWT — {@code INSERT … IF NOT EXISTS} on
 *   like, {@code DELETE … IF EXISTS} on unlike. The {@code wasApplied()}
 *   result tells us whether THIS request actually flipped the row, so the
 *   counter increment / decrement only fires on the winning request. Two
 *   concurrent likes from the same user no longer double-bump the counter.
 *   LWT costs one extra Paxos round-trip vs. a regular write — acceptable
 *   for like-toggle frequency, and the integrity tradeoff is worth it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraReactionService {

    private final ReactionByPostRepository  reactionByPostRepo;
    private final ReactionByUserRepository  reactionByUserRepo;
    private final CommentReactionRepository commentReactionRepo;
    private final CounterService            counterService;
    private final PostRealtimePublisher     realtimePublisher;
    private final PostByIdRepository        postRepo;
    private final CommentLookupRepository   commentLookupRepo;
    private final UserRepository            userRepo;
    private final CassandraNotificationService notificationService;
    private final CqlOperations             cqlOperations;
    private final AuthorAffinityService     affinityService;

    // ── POSTS ────────────────────────────────────────────────────────────────

    /**
     * Toggle a user's like on a post. LWT-guarded so two concurrent toggles
     * from the same user can never both apply: only the request whose
     * {@code IF NOT EXISTS} / {@code IF EXISTS} clause was applied owns the
     * counter delta and the broadcast.
     *
     * @return true if the post is liked AFTER the call, false if it's unliked.
     */
    public boolean togglePostReaction(UUID postId, UUID userId) {
        Optional<ReactionByPostEntity> existing = reactionByPostRepo.find(postId, userId);

        if (existing.isPresent()) {
            ReactionByPostEntity prior = existing.get();
            boolean removed = cqlOperations.execute(
                    "DELETE FROM reactions_by_post WHERE post_id = ? AND user_id = ? IF EXISTS",
                    postId, userId);
            if (!removed) {
                // Lost the race: a concurrent toggle already removed the row.
                // No counter change, no broadcast — state is unliked.
                return false;
            }
            reactionByUserRepo.delete(userId, prior.getCreatedAt(), postId);
            counterService.decrementPostReactions(postId);
            broadcastPostReaction(postId, userId, PostRealtimeEventType.REACTION_REMOVED);
            return false;
        }

        Instant now = Instant.now();
        boolean inserted = cqlOperations.execute(
                "INSERT INTO reactions_by_post (post_id, user_id, created_at) " +
                        "VALUES (?, ?, ?) IF NOT EXISTS",
                postId, userId, now);
        if (!inserted) {
            // Lost the race: a concurrent toggle already inserted. The other
            // request owns the counter + broadcast + notification. State is liked.
            return true;
        }
        reactionByUserRepo.save(ReactionByUserEntity.builder()
                .userId(userId).createdAt(now).postId(postId).build());
        counterService.incrementPostReactions(postId);
        broadcastPostReaction(postId, userId, PostRealtimeEventType.REACTION_ADDED);
        notifyPostReaction(postId, userId);
        affinityService.recordForPost(userId, postId, AuthorAffinityService.WEIGHT_REACTION);
        return true;
    }

    public boolean hasUserReacted(UUID postId, UUID userId) {
        return reactionByPostRepo.find(postId, userId).isPresent();
    }

    public List<ReactionByUserEntity> recentForUser(UUID userId, int pageSize) {
        return reactionByUserRepo.recent(userId, pageSize);
    }

    // ── COMMENTS ─────────────────────────────────────────────────────────────

    /** Idempotent unlike — no-op if the user isn't currently liking this comment. */
    public void removeCommentReaction(UUID commentId, UUID postId, UUID userId) {
        if (commentReactionRepo.find(commentId, userId).isEmpty()) return;
        boolean removed = cqlOperations.execute(
                "DELETE FROM comment_reactions_by_comment " +
                        "WHERE comment_id = ? AND user_id = ? IF EXISTS",
                commentId, userId);
        if (!removed) return;   // lost the race
        counterService.decrementCommentReactions(commentId);
        broadcastCommentReaction(postId, commentId, userId,
                                 PostRealtimeEventType.COMMENT_REACTION_REMOVED);
    }

    /** Idempotent unlike for a post. */
    public void removePostReaction(UUID postId, UUID userId) {
        if (!hasUserReacted(postId, userId)) return;
        togglePostReaction(postId, userId);   // flips true → false (LWT-guarded)
    }

    /**
     * Toggle a user's like on a comment. LWT-guarded — see
     * {@link #togglePostReaction(UUID, UUID)} for the race-correctness notes.
     */
    public boolean toggleCommentReaction(UUID commentId, UUID postId, UUID userId) {
        Optional<CommentReactionEntity> existing = commentReactionRepo.find(commentId, userId);

        if (existing.isPresent()) {
            boolean removed = cqlOperations.execute(
                    "DELETE FROM comment_reactions_by_comment " +
                            "WHERE comment_id = ? AND user_id = ? IF EXISTS",
                    commentId, userId);
            if (!removed) return false;   // lost the race; state already unliked
            counterService.decrementCommentReactions(commentId);
            broadcastCommentReaction(postId, commentId, userId,
                                     PostRealtimeEventType.COMMENT_REACTION_REMOVED);
            return false;
        }

        Instant now = Instant.now();
        boolean inserted = cqlOperations.execute(
                "INSERT INTO comment_reactions_by_comment (comment_id, user_id, created_at) " +
                        "VALUES (?, ?, ?) IF NOT EXISTS",
                commentId, userId, now);
        if (!inserted) return true;       // lost the race; state already liked
        counterService.incrementCommentReactions(commentId);
        broadcastCommentReaction(postId, commentId, userId,
                                 PostRealtimeEventType.COMMENT_REACTION_ADDED);
        notifyCommentReaction(commentId, userId);
        return true;
    }

    // ── Notification fan-out (async — enrichment runs on the executor) ─────
    // We hand a Supplier to deliverAsync(Supplier) so the post/user reads
    // happen on the background thread, not the request thread. A viral post
    // taking 100 likes/sec used to hammer its own posts_by_id partition 100
    // times/sec from the inbound request threads; now those reads are out
    // of the user-facing path entirely.

    private void notifyPostReaction(UUID postId, UUID actorId) {
        notificationService.deliverAsync(() -> {
            PostByIdEntity post = postRepo.findById(postId).orElse(null);
            if (post == null || post.getAuthorId() == null) return null;
            String label = actorLabel(actorId);
            return new CassandraNotificationService.DeliverRequest(
                    post.getAuthorId(),
                    NotificationKind.POST_REACTED,
                    "Someone liked your post",
                    label + " liked your post",
                    actorId,
                    "Post", postId,
                    "POST_REACTED:" + postId
            );
        });
    }

    private void notifyCommentReaction(UUID commentId, UUID actorId) {
        notificationService.deliverAsync(() -> {
            CommentLookupEntity meta = commentLookupRepo.findById(commentId).orElse(null);
            if (meta == null || meta.getAuthorId() == null) return null;
            String label = actorLabel(actorId);
            return new CassandraNotificationService.DeliverRequest(
                    meta.getAuthorId(),
                    NotificationKind.POST_COMMENT_REACTED,
                    "Someone liked your comment",
                    label + " liked your comment",
                    actorId,
                    "Comment", commentId,
                    "POST_COMMENT_REACTED:" + commentId
            );
        });
    }

    /** Renders "@username" or falls back to "Someone" — used in body text. */
    private String actorLabel(UUID actorId) {
        if (actorId == null) return "Someone";
        return userRepo.findById(actorId)
                .map(User::getUsername)
                .map(u -> "@" + u)
                .orElse("Someone");
    }

    // ── realtime ─────────────────────────────────────────────────────────────

    // Counter columns are eventually consistent in Cassandra: re-reading right
    // after an UPDATE can return a stale value, so we don't include a fresh
    // count in realtime events. Clients apply the +1 / -1 delta locally from
    // the event type and reconcile with the canonical count on the next REST
    // fetch. This also removes one synchronous Cassandra read per toggle.
    private void broadcastPostReaction(UUID postId, UUID actorId, PostRealtimeEventType type) {
        if (type == null) return;
        try {
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(type)
                    .postId(postId)
                    .actorId(actorId)
                    .reactionType("LIKE")
                    .build());
        } catch (Exception e) {
            log.debug("[REACT] realtime broadcast skipped: {}", e.getMessage());
        }
    }

    private void broadcastCommentReaction(UUID postId, UUID commentId, UUID actorId,
                                          PostRealtimeEventType type) {
        try {
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(type)
                    .postId(postId)
                    .commentId(commentId)
                    .actorId(actorId)
                    .reactionType("LIKE")
                    .build());
        } catch (Exception e) {
            log.debug("[REACT] comment realtime broadcast skipped: {}", e.getMessage());
        }
    }

}
