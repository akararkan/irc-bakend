package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.CommentLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.CommentReactionEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReactionByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.ReactionByUserEntity;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.repository.CommentCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentReactionRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByUserRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Race notes:
 *   No CAS / LWT — the cost of one stale double-like vs. a Paxos round-trip on
 *   every like isn't worth it at scale. A periodic counter reconciler job
 *   (already exists for the JPA path) can sweep drift.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraReactionService {

    private final ReactionByPostRepository  reactionByPostRepo;
    private final ReactionByUserRepository  reactionByUserRepo;
    private final CommentReactionRepository commentReactionRepo;
    private final PostCounterRepository     postCounterRepo;
    private final CommentCounterRepository  commentCounterRepo;
    private final CounterService            counterService;
    private final PostRealtimePublisher     realtimePublisher;
    private final PostByIdRepository        postRepo;
    private final CommentLookupRepository   commentLookupRepo;
    private final UserRepository            userRepo;
    private final CassandraNotificationService notificationService;

    // ── POSTS ────────────────────────────────────────────────────────────────

    /**
     * Toggle a user's like on a post.
     *
     * @return true if the post is liked AFTER the call, false if it's unliked.
     */
    public boolean togglePostReaction(UUID postId, UUID userId) {
        Optional<ReactionByPostEntity> existing = reactionByPostRepo.find(postId, userId);

        if (existing.isPresent()) {
            ReactionByPostEntity prior = existing.get();
            reactionByPostRepo.delete(postId, userId);
            reactionByUserRepo.delete(userId, prior.getCreatedAt(), postId);
            counterService.decrementPostReactions(postId);
            broadcastPostReaction(postId, userId, PostRealtimEventOrNull(PostRealtimeEventType.REACTION_REMOVED));
            return false;
        }

        Instant now = Instant.now();
        reactionByPostRepo.save(ReactionByPostEntity.builder()
                .postId(postId).userId(userId).createdAt(now).build());
        reactionByUserRepo.save(ReactionByUserEntity.builder()
                .userId(userId).createdAt(now).postId(postId).build());
        counterService.incrementPostReactions(postId);
        broadcastPostReaction(postId, userId, PostRealtimEventOrNull(PostRealtimeEventType.REACTION_ADDED));
        notifyPostReaction(postId, userId);
        return true;
    }

    public boolean hasUserReacted(UUID postId, UUID userId) {
        return reactionByPostRepo.find(postId, userId).isPresent();
    }

    public List<ReactionByUserEntity> recentForUser(UUID userId, int pageSize) {
        return reactionByUserRepo.recent(userId, pageSize);
    }

    // ── COMMENTS ─────────────────────────────────────────────────────────────

    /** Toggle a user's like on a comment. Returns post-toggle liked state. */
    public boolean toggleCommentReaction(UUID commentId, UUID postId, UUID userId) {
        Optional<CommentReactionEntity> existing = commentReactionRepo.find(commentId, userId);

        if (existing.isPresent()) {
            commentReactionRepo.delete(commentId, userId);
            counterService.decrementCommentReactions(commentId);
            broadcastCommentReaction(postId, commentId, userId,
                                     PostRealtimeEventType.COMMENT_REACTION_REMOVED);
            return false;
        }

        commentReactionRepo.save(CommentReactionEntity.builder()
                .commentId(commentId).userId(userId).createdAt(Instant.now()).build());
        counterService.incrementCommentReactions(commentId);
        broadcastCommentReaction(postId, commentId, userId,
                                 PostRealtimeEventType.COMMENT_REACTION_ADDED);
        notifyCommentReaction(commentId, userId);
        return true;
    }

    // ── Notification fan-out (async via deliverAsync) ───────────────────────

    private void notifyPostReaction(UUID postId, UUID actorId) {
        try {
            PostByIdEntity post = postRepo.findById(postId).orElse(null);
            if (post == null || post.getAuthorId() == null) return;
            String actorLabel = actorLabel(actorId);
            notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                    post.getAuthorId(),
                    NotificationKind.POST_REACTED,
                    "Someone liked your post",
                    actorLabel + " liked your post",
                    actorId,
                    "Post", postId,
                    "POST_REACTED:" + postId
            ));
        } catch (Exception e) {
            log.debug("[REACT] notify skipped: {}", e.getMessage());
        }
    }

    private void notifyCommentReaction(UUID commentId, UUID actorId) {
        try {
            CommentLookupEntity meta = commentLookupRepo.findById(commentId).orElse(null);
            if (meta == null || meta.getAuthorId() == null) return;
            String actorLabel = actorLabel(actorId);
            notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                    meta.getAuthorId(),
                    NotificationKind.POST_COMMENT_REACTED,
                    "Someone liked your comment",
                    actorLabel + " liked your comment",
                    actorId,
                    "Comment", commentId,
                    "POST_COMMENT_REACTED:" + commentId
            ));
        } catch (Exception e) {
            log.debug("[REACT] comment notify skipped: {}", e.getMessage());
        }
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

    private void broadcastPostReaction(UUID postId, UUID actorId, PostRealtimeEventType type) {
        if (type == null) return;
        try {
            Long latest = postCounterRepo.findByPostId(postId)
                    .map(c -> c.getReactionCount())
                    .orElse(null);
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(type)
                    .postId(postId)
                    .actorId(actorId)
                    .reactionType("LIKE")
                    .postReactionCount(latest)
                    .build());
        } catch (Exception e) {
            log.debug("[REACT] realtime broadcast skipped: {}", e.getMessage());
        }
    }

    private void broadcastCommentReaction(UUID postId, UUID commentId, UUID actorId,
                                          PostRealtimeEventType type) {
        try {
            Long latest = commentCounterRepo.findByCommentId(commentId)
                    .map(c -> c.getReactionCount())
                    .orElse(null);
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(type)
                    .postId(postId)
                    .commentId(commentId)
                    .actorId(actorId)
                    .reactionType("LIKE")
                    .commentReactionCount(latest)
                    .build());
        } catch (Exception e) {
            log.debug("[REACT] comment realtime broadcast skipped: {}", e.getMessage());
        }
    }

    // Defensive: if a null event type ever slips through, just skip silently.
    private static PostRealtimeEventType PostRealtimEventOrNull(PostRealtimeEventType t) { return t; }
}
