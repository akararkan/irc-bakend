package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.FeedByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.FriendSuggestionEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReactionByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.entity.ReplyByCommentEntity;
import ak.dev.irc.app.post.cassandra.entity.SaveByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.ShareByPostEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraCommentService;
import ak.dev.irc.app.post.cassandra.service.CassandraPostService;
import ak.dev.irc.app.post.cassandra.service.CassandraReactionService;
import ak.dev.irc.app.post.cassandra.service.CassandraSaveService;
import ak.dev.irc.app.post.cassandra.service.CassandraShareService;
import ak.dev.irc.app.post.cassandra.service.CassandraViewService;
import ak.dev.irc.app.post.cassandra.service.FeedTimelineService;
import ak.dev.irc.app.post.cassandra.service.FriendSuggestionService;
import ak.dev.irc.app.post.search.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints backed by Cassandra + Elasticsearch.
 *
 * Namespaced under /api/v1/posts to keep the legacy /api/v1/posts JPA paths
 * working during the migration. Once the JPA paths are removed this can be
 * renamed.
 */
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class CassandraFeedController {

    private final CassandraPostService     postService;
    private final FeedTimelineService      feedService;
    private final FriendSuggestionService  suggestionService;
    private final PostSearchService        searchService;
    private final CassandraReactionService reactionService;
    private final CassandraViewService     viewService;
    private final CassandraCommentService  commentService;
    private final CassandraSaveService     saveService;
    private final CassandraShareService    shareService;

    @PostMapping
    public ResponseEntity<PostByIdEntity> create(@RequestBody CassandraPostService.CreatePostCommand cmd) {
        return ResponseEntity.ok(postService.createPost(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PostByIdEntity> get(@PathVariable UUID id) {
        PostByIdEntity post = postService.getById(id);
        return post == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(post);
    }

    /** Profile feed — newest first, cursor-paginated. */
    @GetMapping("/by-author/{authorId}")
    public List<PostByAuthorEntity> profileFeed(@PathVariable UUID authorId,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? postService.profileFeed(authorId, pageSize)
                : postService.profileFeedAfter(authorId, cursor, pageSize);
    }

    /** Home timeline (fanout-on-write). */
    @GetMapping("/feed")
    public List<FeedByUserEntity> homeFeed(@RequestParam UUID userId,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? feedService.homeFeed(userId, pageSize)
                : feedService.homeFeedAfter(userId, cursor, pageSize);
    }

    /** Global reels — today's bucket by default. */
    @GetMapping("/reels")
    public List<ReelsByDayEntity> reels(@RequestParam(required = false) String day,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        String bucket = day != null ? day : LocalDate.now(ZoneOffset.UTC).toString();
        return postService.reelsForDay(bucket, pageSize);
    }

    /** Elasticsearch full-text search. Returns post UUIDs ranked by relevance. */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "0")  int page,
                                      @RequestParam(defaultValue = "20") int size) {
        List<UUID> ids = searchService.search(q, page, size);
        return Map.of("query", q, "page", page, "size", size, "results", ids);
    }

    /** Friend suggestions — already sorted by mutual-count DESC at the table level. */
    @GetMapping("/suggestions")
    public List<FriendSuggestionEntity> suggestions(@RequestParam UUID userId,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return suggestionService.topSuggestionsFor(userId, limit);
    }

    /** Trigger a recompute (e.g. from a /follow webhook). */
    @PostMapping("/suggestions/recompute")
    public ResponseEntity<Void> recompute(@RequestParam UUID userId) {
        suggestionService.recomputeFor(userId);
        return ResponseEntity.accepted().build();
    }

    // ── Reactions ────────────────────────────────────────────────────────────

    /** Toggle a like on a post. Returns {liked: true|false} after the toggle. */
    @PostMapping("/{postId}/reactions")
    public Map<String, Object> togglePostReaction(@PathVariable UUID postId,
                                                  @RequestParam UUID userId) {
        return Map.of("postId", postId, "userId", userId,
                      "liked", reactionService.togglePostReaction(postId, userId));
    }

    /** "Did I like this?" — used by post-detail rendering. */
    @GetMapping("/{postId}/reactions/me")
    public Map<String, Object> hasReacted(@PathVariable UUID postId,
                                          @RequestParam UUID userId) {
        return Map.of("postId", postId, "userId", userId,
                      "liked", reactionService.hasUserReacted(postId, userId));
    }

    /** Reaction history for a user (newest first). */
    @GetMapping("/users/{userId}/reactions")
    public List<ReactionByUserEntity> userReactions(@PathVariable UUID userId,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        return reactionService.recentForUser(userId, pageSize);
    }

    /** Toggle a like on a comment. */
    @PostMapping("/{postId}/comments/{commentId}/reactions")
    public Map<String, Object> toggleCommentReaction(@PathVariable UUID postId,
                                                     @PathVariable UUID commentId,
                                                     @RequestParam UUID userId) {
        return Map.of("commentId", commentId, "userId", userId,
                      "liked", reactionService.toggleCommentReaction(commentId, postId, userId));
    }

    // ── Views ────────────────────────────────────────────────────────────────

    /** Record a view. Idempotent per user — repeat calls don't inflate the count. */
    @PostMapping("/{postId}/views")
    public Map<String, Object> recordView(@PathVariable UUID postId,
                                          @RequestParam UUID userId) {
        boolean counted = viewService.recordView(postId, userId);
        return Map.of("postId", postId, "userId", userId, "counted", counted,
                      "viewCount", viewService.viewCount(postId));
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    public record CreateCommentRequest(UUID authorId, String text,
                                       String mediaUrl, String mediaType) {}
    public record CreateReplyRequest(UUID authorId, String text, String mediaUrl) {}
    public record EditCommentRequest(UUID authorId, String text) {}

    /** Create a top-level comment on a post. */
    @PostMapping("/{postId}/comments")
    public CommentByPostEntity createComment(@PathVariable UUID postId,
                                             @RequestBody CreateCommentRequest body) {
        return commentService.createComment(postId, body.authorId(),
                                            body.text(), body.mediaUrl(), body.mediaType());
    }

    /** List top-level comments on a post (chronological), cursor-paginated. */
    @GetMapping("/{postId}/comments")
    public List<CommentByPostEntity> listComments(@PathVariable UUID postId,
                                                  @RequestParam(defaultValue = "20") int pageSize,
                                                  @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? commentService.commentsForPost(postId, pageSize)
                : commentService.commentsForPostAfter(postId, cursor, pageSize);
    }

    /**
     * Reply to a comment. If the target is itself a reply, the new reply lands
     * as a sibling under the same top-level parent (depth-1 rule).
     */
    @PostMapping("/comments/{commentId}/replies")
    public ReplyByCommentEntity replyTo(@PathVariable UUID commentId,
                                        @RequestBody CreateReplyRequest body) {
        return commentService.replyTo(commentId, body.authorId(), body.text(), body.mediaUrl());
    }

    /** List replies under a top-level comment (chronological). */
    @GetMapping("/comments/{commentId}/replies")
    public List<ReplyByCommentEntity> listReplies(@PathVariable UUID commentId,
                                                  @RequestParam(defaultValue = "20") int pageSize) {
        return commentService.repliesFor(commentId, pageSize);
    }

    /** Edit a comment or reply — only the author can edit. */
    @PatchMapping("/comments/{commentId}")
    public ResponseEntity<Void> editComment(@PathVariable UUID commentId,
                                            @RequestBody EditCommentRequest body) {
        commentService.editComment(commentId, body.authorId(), body.text());
        return ResponseEntity.noContent().build();
    }

    /** Soft-delete a comment or reply — only the author can delete. */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable UUID commentId,
                                              @RequestParam UUID authorId) {
        commentService.deleteComment(commentId, authorId);
        return ResponseEntity.noContent().build();
    }

    // ── Saves ────────────────────────────────────────────────────────────────

    /** Toggle a save (bookmark). Returns {saved: true|false} after the toggle. */
    @PostMapping("/{postId}/saves")
    public Map<String, Object> toggleSave(@PathVariable UUID postId,
                                          @RequestParam UUID userId,
                                          @RequestParam(required = false) String collection) {
        boolean saved = saveService.toggleSave(postId, userId, collection);
        return Map.of("postId", postId, "userId", userId, "saved", saved);
    }

    /** "Did I save this?" — used by the post-detail bookmark icon. */
    @GetMapping("/{postId}/saves/me")
    public Map<String, Object> isSaved(@PathVariable UUID postId,
                                       @RequestParam UUID userId) {
        return Map.of("postId", postId, "userId", userId,
                      "saved", saveService.isSaved(postId, userId));
    }

    /** A user's saved posts (newest first), cursor-paginated. */
    @GetMapping("/users/{userId}/saves")
    public List<SaveByUserEntity> userSaves(@PathVariable UUID userId,
                                            @RequestParam(defaultValue = "20") int pageSize,
                                            @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? saveService.savesForUser(userId, pageSize)
                : saveService.savesForUserAfter(userId, cursor, pageSize);
    }

    // ── Shares ───────────────────────────────────────────────────────────────

    public record RecordShareRequest(UUID sharerId, String caption) {}

    /** Record a platform-share event on a post (append-only ledger). */
    @PostMapping("/{postId}/shares")
    public ShareByPostEntity recordShare(@PathVariable UUID postId,
                                         @RequestBody RecordShareRequest body) {
        return shareService.recordShare(postId, body.sharerId(), body.caption());
    }

    /** Recent shares on a post — used by the share-stats panel. */
    @GetMapping("/{postId}/shares")
    public List<ShareByPostEntity> shares(@PathVariable UUID postId,
                                          @RequestParam(defaultValue = "20") int pageSize) {
        return shareService.recentShares(postId, pageSize);
    }
}
