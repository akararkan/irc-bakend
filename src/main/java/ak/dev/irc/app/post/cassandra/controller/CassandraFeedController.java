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
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.post.cassandra.service.CassandraCommentService;
import ak.dev.irc.app.post.cassandra.service.CassandraPostService;
import ak.dev.irc.app.post.cassandra.service.CassandraReactionService;
import ak.dev.irc.app.post.cassandra.service.CassandraSaveService;
import ak.dev.irc.app.post.cassandra.service.CassandraShareService;
import ak.dev.irc.app.post.cassandra.service.CassandraViewService;
import ak.dev.irc.app.post.cassandra.service.FeedTimelineService;
import ak.dev.irc.app.post.cassandra.service.FriendSuggestionService;
import ak.dev.irc.app.post.cassandra.service.ReelFeedService;
import ak.dev.irc.app.post.cassandra.service.PostHydrator;
import ak.dev.irc.app.post.dto.FeedItemResponse;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.realtime.PostRealtimeService;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import ak.dev.irc.app.common.exception.ForbiddenException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.ArrayList;

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
@Slf4j
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class CassandraFeedController {

    private final CassandraPostService     postService;
    private final FeedTimelineService      feedService;
    private final ReelFeedService          reelFeedService;
    private final FriendSuggestionService  suggestionService;
    private final CassandraReactionService reactionService;
    private final CassandraViewService     viewService;
    private final CassandraCommentService  commentService;
    private final CassandraSaveService     saveService;
    private final CassandraShareService    shareService;
    private final PostHydrator             hydrator;
    private final PostRealtimeService      postRealtimeService;
    private final S3StorageService         storageService;
    private final JwtTokenProvider         jwtTokenProvider;
    /** Per-user write throttling; fail-open if Redis is down. */
    private final RateLimiter              rateLimiter;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PostResponse> create(@RequestBody CassandraPostService.CreatePostCommand cmd,
                                               @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        rateLimiter.checkSocial(user.getId());
        // authorId is server-derived from the JWT — body-supplied authorId is ignored.
        CassandraPostService.CreatePostCommand authed = new CassandraPostService.CreatePostCommand(
                user.getId(),
                cmd.postType(),
                cmd.visibility(),
                cmd.textContent(),
                cmd.audioTrackUrl(),
                cmd.audioTrackName(),
                cmd.locationName(),
                cmd.locationLat(),
                cmd.locationLng(),
                cmd.sharedPostId(),
                cmd.shareLink(),
                cmd.mediaUrls(),
                cmd.mediaTypes(),
                cmd.soundId());
        return ResponseEntity.ok(hydrator.hydrate(postService.createPost(authed)));
    }

    /**
     * Multipart create — used by the frontend when uploading photos / videos
     * directly with the post (reels, image posts). All form fields are
     * optional except {@code postType}. Files are streamed to R2 and the
     * resulting public URLs are written into the post.
     */
    /**
     * Multipart create — used by the frontend when uploading photos / videos
     * directly with the post (reels, image posts).
     *
     * <p>Robust by design:
     * <ul>
     *   <li>Form fields are read directly from {@code MultipartHttpServletRequest}
     *       so they bind reliably regardless of how Spring resolved
     *       {@code @RequestParam} for this multipart endpoint.</li>
     *   <li>Files are collected from <strong>every</strong> part name
     *       (`files`, `files[]`, `media`, `media[]`, `file`, `video`, `videos`,
     *       `image`, `images` — all work).</li>
     *   <li>If the post create fails after R2 uploads have happened, each
     *       successfully-uploaded S3 object is deleted to avoid orphan files
     *       on the bucket. The frontend gets a structured 500 with the cause.</li>
     * </ul>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createMultipart(MultipartHttpServletRequest request,
                                             @AuthenticationPrincipal User user) {

        if (user == null) throw new UnauthorizedException("Authentication required");
        // Throttle BEFORE the R2 upload — a banned user shouldn't waste
        // bandwidth + storage round-trips just to be rejected at the end.
        rateLimiter.checkSocial(user.getId());

        // 1) Read form fields straight from the request — robust against
        //    @RequestParam quirks with mixed multipart binding.
        String postType      = paramOr(request, "postType",   "POST");
        String visibility    = paramOr(request, "visibility", "PUBLIC");
        String textContent   = paramOr(request, "textContent", null);
        String audioTrackUrl = paramOr(request, "audioTrackUrl", null);
        String audioTrackName= paramOr(request, "audioTrackName", null);
        String locationName  = paramOr(request, "locationName", null);
        Double locationLat   = parseDouble(paramOr(request, "locationLat", null));
        Double locationLng   = parseDouble(paramOr(request, "locationLng", null));
        UUID   sharedPostId  = parseUuid(paramOr(request, "sharedPostId", null));
        String shareLink     = paramOr(request, "shareLink", null);
        UUID   soundId       = parseUuid(paramOr(request, "soundId", null));

        // 2) Upload each file to R2. Track successful keys so we can roll back
        //    if anything below fails.
        List<String> uploadedKeys = new ArrayList<>();
        List<String> mediaUrls    = new ArrayList<>();
        List<String> mediaTypes   = new ArrayList<>();
        try {
            for (List<MultipartFile> bucket : request.getMultiFileMap().values()) {
                for (MultipartFile f : bucket) {
                    if (f == null || f.isEmpty()) continue;
                    String key = storageService.upload(f, "posts/media");
                    uploadedKeys.add(key);
                    mediaUrls.add(storageService.getPublicUrl(key));
                    mediaTypes.add(classifyMedia(f.getContentType()));
                }
            }
        } catch (Exception e) {
            rollbackR2(uploadedKeys);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "upload_failed", "message", String.valueOf(e.getMessage())));
        }

        // 3) Persist the post. On any DB failure, clean up R2 so we never
        //    leave orphaned files paid-for in R2 but not addressable from any post.
        try {
            CassandraPostService.CreatePostCommand authed =
                    new CassandraPostService.CreatePostCommand(
                            user.getId(), postType, visibility, textContent,
                            audioTrackUrl, audioTrackName,
                            locationName, locationLat, locationLng,
                            sharedPostId, shareLink,
                            mediaUrls, mediaTypes, soundId);
            PostByIdEntity created = postService.createPost(authed);
            return ResponseEntity.ok(hydrator.hydrate(created));
        } catch (Exception e) {
            rollbackR2(uploadedKeys);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "post_create_failed",
                            "message", String.valueOf(e.getMessage()),
                            "rolledBackFiles", uploadedKeys.size()));
        }
    }

    /** Delete previously-uploaded R2 keys after a failure to avoid orphans. */
    private void rollbackR2(List<String> keys) {
        for (String k : keys) {
            try { storageService.delete(k); }
            catch (Exception ignore) { /* best-effort */ }
        }
    }

    private static String paramOr(MultipartHttpServletRequest req, String name, String fallback) {
        String v = req.getParameter(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** Map an HTTP content-type to a coarse media bucket the UI cares about. */
    private static String classifyMedia(String contentType) {
        if (contentType == null) return "OTHER";
        String ct = contentType.toLowerCase();
        if (ct.startsWith("image/")) return "IMAGE";
        if (ct.startsWith("video/")) return "VIDEO";
        if (ct.startsWith("audio/")) return "AUDIO";
        return "OTHER";
    }

    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> get(@PathVariable UUID id) {
        PostByIdEntity post = postService.getById(id);
        return post == null ? ResponseEntity.notFound().build()
                            : ResponseEntity.ok(hydrator.hydrate(post));
    }

    /**
     * Partial-update a post. Author-only.
     *
     * <p>Every field on the body is optional — null leaves the existing
     * value untouched. Broadcasts {@code POST_UPDATED} on the post's
     * realtime channel so open viewers reconcile without a refetch.</p>
     */
    @PatchMapping("/{id}")
    public ResponseEntity<PostResponse> editPost(@PathVariable UUID id,
                                                 @RequestBody CassandraPostService.EditPostCommand body,
                                                 @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        try {
            PostByIdEntity updated = postService.editPost(id, user.getId(), body);
            if (updated == null) return ResponseEntity.notFound().build();

            // Realtime: notify every open viewer that the post body changed.
            try {
                postRealtimeService.broadcast(id,
                        ak.dev.irc.app.post.realtime.PostRealtimeEvent.builder()
                                .eventType(ak.dev.irc.app.post.realtime.PostRealtimeEventType.POST_UPDATED)
                                .postId(id)
                                .actorId(user.getId())
                                .textContent(updated.getTextContent())
                                .build());
            } catch (Exception ignore) { /* realtime is best-effort */ }

            return ResponseEntity.ok(hydrator.hydrate(updated));
        } catch (SecurityException e) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
    }

    /** Delete a post — only the author may delete their own post. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(@PathVariable UUID id,
                                           @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        PostByIdEntity existing = postService.getById(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getId().equals(existing.getAuthorId())) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
        postService.deletePost(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Live event stream for a single post — reactions, comments, view/save/share
     * counter updates. Mirrors the SSE pattern used by Q&A and research.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPost(@PathVariable UUID id,
                                 @RequestParam(value = "token", required = false) String token,
                                 @AuthenticationPrincipal User user,
                                 HttpServletResponse response) {
        UUID viewerId = user != null ? user.getId() : null;

        // EventSource can't send Authorization headers — accept ?token=<jwt>
        // as a fallback, matching NotificationController/UserActivityController.
        if (viewerId == null && StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)
                        && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                    viewerId = jwtTokenProvider.getUserIdFromToken(token);
                }
            } catch (Exception ex) {
                log.warn("[POST-SSE] Invalid token supplied via query param: {}", ex.getMessage());
            }
        }

        // Anon viewers are still allowed (post stream is public) — only set
        // anti-buffering headers; subscribe with viewerId=null is fine.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");

        return postRealtimeService.subscribe(id, viewerId);
    }

    /** Profile feed — newest first, cursor-paginated. */
    @GetMapping("/by-author/{authorId}")
    public List<FeedItemResponse> profileFeed(@PathVariable UUID authorId,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) Instant cursor) {
        int size = Pages.clamp(pageSize);
        return hydrator.hydrateProfileFeed(cursor == null
                ? postService.profileFeed(authorId, size)
                : postService.profileFeedAfter(authorId, cursor, size));
    }

    /**
     * Home timeline (fanout-on-write).
     * Path aliases: {@code /feed} (canonical) and {@code /feed/cursor} (legacy
     * URL preserved from the pre-Cassandra controller so the frontend works
     * without changes). Both accept the same params.
     */
    @GetMapping({"/feed", "/feed/cursor"})
    public List<FeedItemResponse> homeFeed(@RequestParam(required = false) UUID userId,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           @RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(required = false) Instant cursor,
                                           @AuthenticationPrincipal User user) {
        int size = Pages.clamp(limit > 0 ? limit : pageSize);
        // Prefer the JWT principal; fall back to ?userId= for legacy clients.
        UUID viewer = user != null ? user.getId() : userId;
        if (viewer == null) return List.of();
        return hydrator.hydrateHomeFeed(cursor == null
                ? feedService.homeFeed(viewer, size)
                : feedService.homeFeedAfter(viewer, cursor, size));
    }

    /**
     * Global reels — today's UTC day bucket by default.
     * Path aliases: {@code /reels} (canonical) and {@code /feed/reels} (legacy).
     * Accepts both {@code pageSize} (canonical) and {@code size/page} (legacy).
     */
    @GetMapping({"/reels", "/feed/reels"})
    public List<FeedItemResponse> reels(@RequestParam(required = false) String day,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(defaultValue = "0")  int page) {
        int effective = Pages.clamp(size > 0 ? size : pageSize);
        String bucket = day != null ? day : LocalDate.now(ZoneOffset.UTC).toString();
        return hydrator.hydrateReels(postService.reelsForDay(bucket, effective));
    }

    /**
     * Reels from accounts the viewer follows, newest first.
     * Parallel fan-in from posts_by_author per followed author; merge-sorted.
     * Requires auth — returns empty list for anonymous callers.
     */
    @GetMapping("/reels/following")
    public List<FeedItemResponse> followingReels(@RequestParam(defaultValue = "20") int pageSize,
                                                 @RequestParam(required = false) Instant cursor,
                                                 @AuthenticationPrincipal User user) {
        if (user == null) return List.of();
        return hydrator.hydrateProfileFeed(
                reelFeedService.followingReels(user.getId(), Pages.clamp(pageSize), cursor));
    }

    /**
     * Engagement-ranked "For You" reel feed.
     * Scores the last 3 days of reels by (reactions×3 + comments×2 + views) ×
     * recency-decay × following-boost(1.5×). No auth required — anonymous
     * callers get global ranking without the following boost.
     */
    @GetMapping("/reels/for-you")
    public List<FeedItemResponse> forYouReels(@RequestParam(defaultValue = "20") int pageSize,
                                              @AuthenticationPrincipal User user) {
        UUID viewerId = user != null ? user.getId() : null;
        return hydrator.hydrateReels(reelFeedService.forYouReels(viewerId, Pages.clamp(pageSize)));
    }

    /**
     * A single author's reels, newest first, cursor-paginated — the per-author
     * Reels tab on a profile. (The general {@code /by-author/{id}} feed includes
     * reels mixed in; this is the reel-only, correctly-paginated slice.)
     */
    @GetMapping("/reels/by-author/{authorId}")
    public List<FeedItemResponse> reelsByAuthor(@PathVariable UUID authorId,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                @RequestParam(required = false) Instant cursor) {
        int size = Pages.clamp(pageSize);
        return hydrator.hydrateProfileFeed(cursor == null
                ? postService.reelsByAuthor(authorId, size)
                : postService.reelsByAuthorAfter(authorId, cursor, size));
    }

    /**
     * Friend suggestions — already sorted by mutual-count DESC at the table
     * level. Always the CALLER's suggestions: the partition being read is
     * bound to the JWT principal, never a query param, so one user can't
     * browse another user's suggestion graph.
     */
    @GetMapping("/suggestions")
    public List<FriendSuggestionEntity> suggestions(@RequestParam(defaultValue = "20") int limit,
                                                    @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return suggestionService.topSuggestionsFor(user.getId(), Pages.clamp(limit));
    }

    /** Trigger a recompute of the CALLER's suggestions (e.g. after onboarding). */
    @PostMapping("/suggestions/recompute")
    public ResponseEntity<Void> recompute(@AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        suggestionService.recomputeFor(user.getId());
        return ResponseEntity.accepted().build();
    }

    // ── Reactions ────────────────────────────────────────────────────────────

    /** Toggle a like on a post. Returns {liked: true|false} after the toggle. */
    @PostMapping("/{postId}/reactions")
    public ResponseEntity<Map<String, Object>> togglePostReaction(@PathVariable UUID postId,
                                                                  @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        UUID userId = user.getId();
        return ResponseEntity.ok(Map.of("postId", postId, "userId", userId,
                "liked", reactionService.togglePostReaction(postId, userId)));
    }

    /** "Did I like this?" — used by post-detail rendering. */
    @GetMapping("/{postId}/reactions/me")
    public ResponseEntity<Map<String, Object>> hasReacted(@PathVariable UUID postId,
                                                          @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.ok(Map.of("postId", postId, "liked", false));
        UUID userId = user.getId();
        return ResponseEntity.ok(Map.of("postId", postId, "userId", userId,
                "liked", reactionService.hasUserReacted(postId, userId)));
    }

    /** Reaction history for a user (newest first). */
    @GetMapping("/users/{userId}/reactions")
    public List<ReactionByUserEntity> userReactions(@PathVariable UUID userId,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        return reactionService.recentForUser(userId, Pages.clamp(pageSize));
    }

    /** Toggle a like on a comment. */
    @PostMapping("/{postId}/comments/{commentId}/reactions")
    public ResponseEntity<Map<String, Object>> toggleCommentReaction(@PathVariable UUID postId,
                                                                     @PathVariable UUID commentId,
                                                                     @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        UUID userId = user.getId();
        return ResponseEntity.ok(Map.of("commentId", commentId, "userId", userId,
                "liked", reactionService.toggleCommentReaction(commentId, postId, userId)));
    }

    /** Explicit unlike a post (idempotent — no-op if not currently liked). */
    @DeleteMapping("/{postId}/reactions")
    public ResponseEntity<Map<String, Object>> deletePostReaction(@PathVariable UUID postId,
                                                                  @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        reactionService.removePostReaction(postId, user.getId());
        return ResponseEntity.ok(Map.of("postId", postId, "liked", false));
    }

    /** Explicit unlike a comment (idempotent — no-op if not currently liked). */
    @DeleteMapping("/{postId}/comments/{commentId}/reactions")
    public ResponseEntity<Map<String, Object>> deleteCommentReaction(@PathVariable UUID postId,
                                                                     @PathVariable UUID commentId,
                                                                     @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        reactionService.removeCommentReaction(commentId, postId, user.getId());
        return ResponseEntity.ok(Map.of("commentId", commentId, "liked", false));
    }

    // ── Views ────────────────────────────────────────────────────────────────

    /** Record a view. Idempotent per user — repeat calls don't inflate the count.
     *  Anonymous viewers get the count back but don't bump it. */
    @PostMapping("/{postId}/views")
    public Map<String, Object> recordView(@PathVariable UUID postId,
                                          @AuthenticationPrincipal User user) {
        if (user == null) {
            return Map.of("postId", postId, "counted", false,
                          "viewCount", viewService.viewCount(postId));
        }
        UUID userId = user.getId();
        boolean counted = viewService.recordView(postId, userId);
        return Map.of("postId", postId, "userId", userId, "counted", counted,
                      "viewCount", viewService.viewCount(postId));
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    public record CreateCommentRequest(String text, String mediaUrl, String mediaType) {}
    public record CreateReplyRequest(String text, String mediaUrl) {}
    public record EditCommentRequest(String text) {}

    /** Create a top-level comment on a post. authorId comes from the JWT. */
    @PostMapping("/{postId}/comments")
    public ResponseEntity<ak.dev.irc.app.post.dto.CommentResponse> createComment(
            @PathVariable UUID postId,
            @RequestBody CreateCommentRequest body,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        rateLimiter.checkComment(user.getId());
        return ResponseEntity.ok(hydrator.hydrate(
                commentService.createComment(postId, user.getId(),
                        body.text(), body.mediaUrl(), body.mediaType())));
    }

    /** List top-level comments on a post (chronological), cursor-paginated. */
    @GetMapping("/{postId}/comments")
    public List<ak.dev.irc.app.post.dto.CommentResponse> listComments(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Instant cursor) {
        return hydrator.hydrateComments(cursor == null
                ? commentService.commentsForPost(postId, Pages.clamp(pageSize))
                : commentService.commentsForPostAfter(postId, cursor, Pages.clamp(pageSize)));
    }

    /**
     * Reply to a comment. If the target is itself a reply, the new reply lands
     * as a sibling under the same top-level parent (depth-1 rule).
     */
    @PostMapping("/comments/{commentId}/replies")
    public ResponseEntity<ak.dev.irc.app.post.dto.ReplyResponse> replyTo(
            @PathVariable UUID commentId,
            @RequestBody CreateReplyRequest body,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        rateLimiter.checkComment(user.getId());
        return ResponseEntity.ok(hydrator.hydrate(
                commentService.replyTo(commentId, user.getId(), body.text(), body.mediaUrl())));
    }

    /** List replies under a top-level comment (chronological). */
    @GetMapping("/comments/{commentId}/replies")
    public List<ak.dev.irc.app.post.dto.ReplyResponse> listReplies(
            @PathVariable UUID commentId,
            @RequestParam(defaultValue = "20") int pageSize) {
        return hydrator.hydrateReplies(commentService.repliesFor(commentId, Pages.clamp(pageSize)));
    }

    /** Edit a comment or reply — only the author can edit. */
    @PatchMapping("/comments/{commentId}")
    public ResponseEntity<Void> editComment(@PathVariable UUID commentId,
                                            @RequestBody EditCommentRequest body,
                                            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        commentService.editComment(commentId, user.getId(), body.text());
        return ResponseEntity.noContent().build();
    }

    /** Soft-delete a comment or reply — only the author can delete. */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable UUID commentId,
                                              @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        commentService.deleteComment(commentId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ── Saves ────────────────────────────────────────────────────────────────

    /** Toggle a save (bookmark). Returns {saved: true|false} after the toggle. */
    @PostMapping("/{postId}/saves")
    public ResponseEntity<Map<String, Object>> toggleSave(@PathVariable UUID postId,
                                                          @RequestParam(required = false) String collection,
                                                          @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        UUID userId = user.getId();
        rateLimiter.checkSocial(userId);
        boolean saved = saveService.toggleSave(postId, userId, collection);
        return ResponseEntity.ok(Map.of("postId", postId, "userId", userId, "saved", saved));
    }

    /** "Did I save this?" — used by the post-detail bookmark icon. */
    @GetMapping("/{postId}/saves/me")
    public ResponseEntity<Map<String, Object>> isSaved(@PathVariable UUID postId,
                                                       @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.ok(Map.of("postId", postId, "saved", false));
        UUID userId = user.getId();
        return ResponseEntity.ok(Map.of("postId", postId, "userId", userId,
                "saved", saveService.isSaved(postId, userId)));
    }

    /** Explicit unsave (idempotent — no-op if not currently saved). */
    @DeleteMapping("/{postId}/saves")
    public ResponseEntity<Map<String, Object>> deleteSave(@PathVariable UUID postId,
                                                          @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        if (saveService.isSaved(postId, user.getId())) {
            saveService.toggleSave(postId, user.getId(), null);   // flips true → false
        }
        return ResponseEntity.ok(Map.of("postId", postId, "saved", false));
    }

    /**
     * A user's saved posts (newest first), cursor-paginated.
     *
     * <p>Returns fully-hydrated {@link PostResponse} objects so the frontend
     * gets {@code response[i].id} = the post UUID (not the save row's
     * {@code postId}). Save-context metadata lives on
     * {@code savedAt} and {@code savedCollectionName}.</p>
     *
     * <p>Saves whose underlying post has been hard-deleted are silently
     * dropped from the response.</p>
     */
    @GetMapping("/users/{userId}/saves")
    public List<PostResponse> userSaves(@PathVariable UUID userId,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(required = false) Instant cursor) {
        List<SaveByUserEntity> rows = cursor == null
                ? saveService.savesForUser(userId, Pages.clamp(pageSize))
                : saveService.savesForUserAfter(userId, cursor, Pages.clamp(pageSize));
        return hydrator.hydrateSavedPosts(rows);
    }

    // ── Shares ───────────────────────────────────────────────────────────────

    public record RecordShareRequest(String caption) {}

    /** Record a platform-share event on a post (append-only ledger).
     *  Sharer derived from JWT — body-supplied sharerId is ignored. */
    @PostMapping("/{postId}/shares")
    public ResponseEntity<ShareByPostEntity> recordShare(@PathVariable UUID postId,
                                                         @RequestBody(required = false) RecordShareRequest body,
                                                         @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        rateLimiter.checkSocial(user.getId());
        String caption = body != null ? body.caption() : null;
        return ResponseEntity.ok(shareService.recordShare(postId, user.getId(), caption));
    }

    /** Recent shares on a post — used by the share-stats panel. */
    @GetMapping("/{postId}/shares")
    public List<ShareByPostEntity> shares(@PathVariable UUID postId,
                                          @RequestParam(defaultValue = "20") int pageSize) {
        return shareService.recentShares(postId, Pages.clamp(pageSize));
    }

    // ── Unified share-link API (parity with research / Q&A) ──────────────────

    /** Preview the unified share-link payload without bumping the counter. */
    @GetMapping("/{postId}/share-link")
    public ResponseEntity<ak.dev.irc.app.share.ShareLinkInfo> previewShareLink(
            @PathVariable UUID postId,
            jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.ok(
                shareService.previewShareLink(postId, ak.dev.irc.app.share.OriginUtil.origin(request)));
    }

    /** Atomically bumps {@code shareCount}, records a share ledger row, and
     *  returns the unified share-link info. Called when the user actually
     *  copies / sends the link. */
    @PostMapping("/{postId}/share")
    public ResponseEntity<ak.dev.irc.app.share.ShareLinkInfo> share(
            @PathVariable UUID postId,
            @RequestBody(required = false) RecordShareRequest body,
            @AuthenticationPrincipal User user,
            jakarta.servlet.http.HttpServletRequest request) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        rateLimiter.checkSocial(user.getId());
        String caption = body != null ? body.caption() : null;
        return ResponseEntity.ok(shareService.recordShareLink(
                postId, user.getId(), caption,
                ak.dev.irc.app.share.OriginUtil.origin(request)));
    }
}
