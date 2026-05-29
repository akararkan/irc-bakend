package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.CommentCounterEntity;
import ak.dev.irc.app.post.cassandra.entity.FeedByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.PostCounterEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.entity.ReplyByCommentEntity;
import ak.dev.irc.app.post.cassandra.entity.SaveByUserEntity;
import ak.dev.irc.app.post.cassandra.repository.CommentCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentReactionRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.SaveLookupRepository;
import ak.dev.irc.app.post.dto.AuthorSummary;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.dto.FeedItemResponse;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.dto.ReplyResponse;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Joins canonical Cassandra rows with author profile data (Postgres) and live
 * counter rows so every post / comment / reply response carries the data the UI
 * needs to render — name, avatar, reaction count, comment count, view count,
 * saved/liked flags — without a single extra round-trip from the frontend.
 *
 * <p>Bulk strategy: for every list-hydration path, we pre-fetch (in 3 parallel
 * driver calls) the post counters, the viewer's reactions across the page, and
 * the viewer's saves across the page. That replaces the prior per-row N+1
 * point-reads (3 × pageSize round-trips → 3 round-trips total).</p>
 *
 * <p>Single-row hydrate variants keep the per-row reads — they're already 1
 * round-trip and don't benefit from batching.</p>
 */
@Service
@RequiredArgsConstructor
public class PostHydrator {

    private final UserRepository             userRepo;
    private final PostByIdRepository         postByIdRepo;
    private final PostCounterRepository      postCounterRepo;
    private final CommentCounterRepository   commentCounterRepo;
    private final ReactionByPostRepository   reactionRepo;
    private final CommentReactionRepository  commentReactionRepo;
    private final SaveLookupRepository       saveRepo;

    // ── single-post hydration ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PostResponse hydrate(PostByIdEntity p) {
        return hydrate(p, /* savedAt */ null, /* savedCollectionName */ null);
    }

    /**
     * Single-post hydration variant used by saved-list endpoints — adds the
     * two save-context fields ({@code savedAt}, {@code savedCollectionName})
     * onto the response. Pass {@code null} for both on every other path.
     */
    @Transactional(readOnly = true)
    public PostResponse hydrate(PostByIdEntity p, Instant savedAt, String savedCollectionName) {
        if (p == null) return null;
        UUID viewerId = currentViewerId();
        PostCounterEntity counters = postCounterRepo.findByPostId(p.getId()).orElse(null);
        boolean likedByMe = viewerId != null && reactionRepo.find(p.getId(), viewerId).isPresent();
        // If we have a savedAt, this row came from the viewer's own save —
        // shortcut savedByMe to true without an extra point read.
        boolean savedByMe = savedAt != null
                || (viewerId != null && saveRepo.find(p.getId(), viewerId).isPresent());
        return buildPostResponse(p, counters, likedByMe, savedByMe, savedAt, savedCollectionName);
    }

    // ── saved-posts hydration ────────────────────────────────────────────────

    /**
     * Hydrate a page of {@link SaveByUserEntity} save rows into full
     * {@link PostResponse} objects. {@code response.id} is the post UUID
     * (NOT the save id), so React keys and {@code /posts/{id}} links work
     * straight out of the box. {@code savedAt} and {@code savedCollectionName}
     * carry the save-row metadata.
     *
     * <p>Rows whose underlying post has been deleted are silently dropped
     * — Cassandra has no FK and the save mirror can outlive the post.</p>
     */
    @Transactional(readOnly = true)
    public List<PostResponse> hydrateSavedPosts(List<SaveByUserEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();

        Set<UUID> postIds = collectIds(rows, SaveByUserEntity::getPostId);
        Map<UUID, PostByIdEntity> postsById = bulkLoadPosts(postIds);
        if (postsById.isEmpty()) return List.of();

        Map<UUID, PostCounterEntity> counters = bulkLoadCounters(postsById.keySet());
        Map<UUID, AuthorSummary> authors = bulkLoadAuthors(postsById.values(), PostByIdEntity::getAuthorId);
        UUID viewerId = currentViewerId();
        Set<UUID> likedSet = bulkLikedPosts(viewerId, postsById.keySet());
        // savedByMe is always true here (we're hydrating the viewer's own saves)
        // but we still need bulk authors/counters and to honor savedAt/collection.

        List<PostResponse> out = new ArrayList<>(rows.size());
        for (SaveByUserEntity r : rows) {
            PostByIdEntity p = postsById.get(r.getPostId());
            if (p == null) continue;
            out.add(buildPostResponse(
                    p,
                    counters.get(p.getId()),
                    likedSet.contains(p.getId()),
                    true,
                    r.getCreatedAt(),
                    r.getCollectionName(),
                    authors.get(p.getAuthorId())));
        }
        return out;
    }

    // ── feed hydration (bulk) ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FeedItemResponse> hydrateHomeFeed(List<FeedByUserEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoadAuthors(rows, FeedByUserEntity::getAuthorId);
        Set<UUID> postIds = collectIds(rows, FeedByUserEntity::getPostId);
        // ONE bulk fetch of the canonical posts so edited text/media reach the
        // feed without the frontend N+1 — feed_by_user.text_preview is a
        // create-time snapshot and is never updated on PATCH (intentional —
        // updating O(followers) rows per edit would crush celebrities). Falls
        // back to the snapshot when the canonical row is gone (deleted post).
        Map<UUID, PostByIdEntity> canonical = bulkLoadPosts(postIds);
        Map<UUID, PostCounterEntity> counters = bulkLoadCounters(postIds);
        UUID viewerId = currentViewerId();
        Set<UUID> likedSet = bulkLikedPosts(viewerId, postIds);
        Set<UUID> savedSet = bulkSavedPosts(viewerId, postIds);

        List<FeedItemResponse> out = new ArrayList<>(rows.size());
        for (FeedByUserEntity r : rows) {
            PostByIdEntity      live = canonical.get(r.getPostId());
            PostCounterEntity   c    = counters.get(r.getPostId());
            out.add(new FeedItemResponse(
                    r.getPostId(),
                    r.getAuthorId(),
                    authors.get(r.getAuthorId()),
                    r.getPostType(),
                    livePreview(live, r.getTextPreview()),
                    liveCoverMedia(live, r.getMediaUrl()),
                    liveVideoUrl(live, r.getPostType()),
                    nullSafe(c == null ? null : c.getReactionCount()),
                    nullSafe(c == null ? null : c.getCommentCount()),
                    nullSafe(c == null ? null : c.getViewCount()),
                    nullSafe(c == null ? null : c.getSaveCount()),
                    nullSafe(c == null ? null : c.getShareCount()),
                    likedSet.contains(r.getPostId()),
                    savedSet.contains(r.getPostId()),
                    r.getCreatedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<FeedItemResponse> hydrateProfileFeed(List<PostByAuthorEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoadAuthors(rows, PostByAuthorEntity::getAuthorId);
        Set<UUID> postIds = collectIds(rows, PostByAuthorEntity::getPostId);
        // Same canonical-hydration as hydrateHomeFeed — posts_by_author IS
        // best-effort mirrored on edit (§6.4) but only when the edit succeeds
        // before the async indexer fires, so a fresh read still wins.
        Map<UUID, PostByIdEntity> canonical = bulkLoadPosts(postIds);
        Map<UUID, PostCounterEntity> counters = bulkLoadCounters(postIds);
        UUID viewerId = currentViewerId();
        Set<UUID> likedSet = bulkLikedPosts(viewerId, postIds);
        Set<UUID> savedSet = bulkSavedPosts(viewerId, postIds);

        List<FeedItemResponse> out = new ArrayList<>(rows.size());
        for (PostByAuthorEntity r : rows) {
            PostByIdEntity      live = canonical.get(r.getPostId());
            PostCounterEntity   c    = counters.get(r.getPostId());
            out.add(new FeedItemResponse(
                    r.getPostId(),
                    r.getAuthorId(),
                    authors.get(r.getAuthorId()),
                    r.getPostType(),
                    livePreview(live, r.getTextPreview()),
                    liveCoverMedia(live, r.getMediaUrl()),
                    liveVideoUrl(live, r.getPostType()),
                    nullSafe(c == null ? null : c.getReactionCount()),
                    nullSafe(c == null ? null : c.getCommentCount()),
                    nullSafe(c == null ? null : c.getViewCount()),
                    nullSafe(c == null ? null : c.getSaveCount()),
                    nullSafe(c == null ? null : c.getShareCount()),
                    likedSet.contains(r.getPostId()),
                    savedSet.contains(r.getPostId()),
                    toInstant(r.getCreatedAt())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<FeedItemResponse> hydrateReels(List<ReelsByDayEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoadAuthors(rows, ReelsByDayEntity::getAuthorId);
        Set<UUID> postIds = collectIds(rows, ReelsByDayEntity::getPostId);
        Map<UUID, PostCounterEntity> counters = bulkLoadCounters(postIds);
        UUID viewerId = currentViewerId();
        Set<UUID> likedSet = bulkLikedPosts(viewerId, postIds);
        Set<UUID> savedSet = bulkSavedPosts(viewerId, postIds);
        Map<UUID, String> videoUrls = bulkVideoUrls(postIds);  // all rows are REELs

        List<FeedItemResponse> out = new ArrayList<>(rows.size());
        for (ReelsByDayEntity r : rows) {
            PostCounterEntity c = counters.get(r.getPostId());
            out.add(new FeedItemResponse(
                    r.getPostId(),
                    r.getAuthorId(),
                    authors.get(r.getAuthorId()),
                    "REEL",
                    r.getTextPreview(),
                    r.getMediaUrl(),
                    videoUrls.get(r.getPostId()),
                    nullSafe(c == null ? null : c.getReactionCount()),
                    nullSafe(c == null ? null : c.getCommentCount()),
                    nullSafe(c == null ? null : c.getViewCount()),
                    nullSafe(c == null ? null : c.getSaveCount()),
                    nullSafe(c == null ? null : c.getShareCount()),
                    likedSet.contains(r.getPostId()),
                    savedSet.contains(r.getPostId()),
                    toInstant(r.getCreatedAt())));
        }
        return out;
    }

    // ── comment hydration ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CommentResponse hydrate(CommentByPostEntity c) {
        if (c == null) return null;
        UUID viewerId = currentViewerId();
        CommentCounterEntity counters = commentCounterRepo.findByCommentId(c.getCommentId()).orElse(null);
        return new CommentResponse(
                c.getCommentId(),
                c.getPostId(),
                c.getAuthorId(),
                authorOf(c.getAuthorId()),
                c.getTextContent(),
                c.getMediaUrl(),
                c.getMediaType(),
                nullSafe(counters == null ? null : counters.getReactionCount()),
                nullSafe(counters == null ? null : counters.getReplyCount()),
                viewerId != null && commentReactionRepo.find(c.getCommentId(), viewerId).isPresent(),
                c.getDeleted(),
                c.getEdited(),
                c.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> hydrateComments(List<CommentByPostEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoadAuthors(rows, CommentByPostEntity::getAuthorId);
        Set<UUID> commentIds = collectIds(rows, CommentByPostEntity::getCommentId);
        Map<UUID, CommentCounterEntity> counters = bulkLoadCommentCounters(commentIds);
        UUID viewerId = currentViewerId();
        Set<UUID> likedCommentSet = bulkLikedComments(viewerId, commentIds);

        List<CommentResponse> out = new ArrayList<>(rows.size());
        for (CommentByPostEntity c : rows) {
            CommentCounterEntity ctr = counters.get(c.getCommentId());
            out.add(new CommentResponse(
                    c.getCommentId(),
                    c.getPostId(),
                    c.getAuthorId(),
                    authors.get(c.getAuthorId()),
                    c.getTextContent(),
                    c.getMediaUrl(),
                    c.getMediaType(),
                    nullSafe(ctr == null ? null : ctr.getReactionCount()),
                    nullSafe(ctr == null ? null : ctr.getReplyCount()),
                    likedCommentSet.contains(c.getCommentId()),
                    c.getDeleted(),
                    c.getEdited(),
                    c.getCreatedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ReplyResponse hydrate(ReplyByCommentEntity r) {
        if (r == null) return null;
        UUID viewerId = currentViewerId();
        CommentCounterEntity counters = commentCounterRepo.findByCommentId(r.getReplyId()).orElse(null);
        return new ReplyResponse(
                r.getReplyId(),
                r.getParentId(),
                r.getPostId(),
                r.getAuthorId(),
                authorOf(r.getAuthorId()),
                r.getTextContent(),
                r.getMediaUrl(),
                nullSafe(counters == null ? null : counters.getReactionCount()),
                viewerId != null && commentReactionRepo.find(r.getReplyId(), viewerId).isPresent(),
                r.getDeleted(),
                r.getEdited(),
                r.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ReplyResponse> hydrateReplies(List<ReplyByCommentEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoadAuthors(rows, ReplyByCommentEntity::getAuthorId);
        Set<UUID> replyIds = collectIds(rows, ReplyByCommentEntity::getReplyId);
        Map<UUID, CommentCounterEntity> counters = bulkLoadCommentCounters(replyIds);
        UUID viewerId = currentViewerId();
        Set<UUID> likedReplySet = bulkLikedComments(viewerId, replyIds);

        List<ReplyResponse> out = new ArrayList<>(rows.size());
        for (ReplyByCommentEntity r : rows) {
            CommentCounterEntity ctr = counters.get(r.getReplyId());
            out.add(new ReplyResponse(
                    r.getReplyId(),
                    r.getParentId(),
                    r.getPostId(),
                    r.getAuthorId(),
                    authors.get(r.getAuthorId()),
                    r.getTextContent(),
                    r.getMediaUrl(),
                    nullSafe(ctr == null ? null : ctr.getReactionCount()),
                    likedReplySet.contains(r.getReplyId()),
                    r.getDeleted(),
                    r.getEdited(),
                    r.getCreatedAt()));
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private PostResponse buildPostResponse(PostByIdEntity p,
                                           PostCounterEntity counters,
                                           boolean likedByMe,
                                           boolean savedByMe,
                                           Instant savedAt,
                                           String savedCollectionName) {
        return buildPostResponse(p, counters, likedByMe, savedByMe, savedAt, savedCollectionName,
                                 authorOf(p.getAuthorId()));
    }

    private PostResponse buildPostResponse(PostByIdEntity p,
                                           PostCounterEntity counters,
                                           boolean likedByMe,
                                           boolean savedByMe,
                                           Instant savedAt,
                                           String savedCollectionName,
                                           AuthorSummary author) {
        return new PostResponse(
                p.getId(),
                p.getAuthorId(),
                author,
                p.getPostType(),
                p.getStatus(),
                p.getVisibility(),
                p.getTextContent(),
                p.getAudioTrackUrl(),
                p.getAudioTrackName(),
                p.getLocationName(),
                p.getLocationLat(),
                p.getLocationLng(),
                p.getSharedPostId(),
                p.getShareLink(),
                p.getMediaUrls(),
                p.getMediaTypes(),
                nullSafe(counters == null ? null : counters.getReactionCount()),
                nullSafe(counters == null ? null : counters.getCommentCount()),
                nullSafe(counters == null ? null : counters.getViewCount()),
                nullSafe(counters == null ? null : counters.getSaveCount()),
                nullSafe(counters == null ? null : counters.getShareCount()),
                likedByMe,
                savedByMe,
                p.getCreatedAt(),
                p.getUpdatedAt(),
                savedAt,
                savedCollectionName);
    }

    private static long nullSafe(Long v) { return v == null ? 0L : v; }

    private UUID currentViewerId() {
        return SecurityUtils.getCurrentUserId().orElse(null);
    }

    private AuthorSummary authorOf(UUID userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(PostHydrator::toSummary).orElse(null);
    }

    private <T> Set<UUID> collectIds(List<T> rows, Function<T, UUID> idFn) {
        Set<UUID> ids = new HashSet<>(rows.size() * 2);
        for (T r : rows) {
            UUID id = idFn.apply(r);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private <T> Map<UUID, AuthorSummary> bulkLoadAuthors(Iterable<T> rows, Function<T, UUID> idFn) {
        Set<UUID> ids = new HashSet<>();
        for (T r : rows) {
            UUID id = idFn.apply(r);
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return Map.of();
        Map<UUID, AuthorSummary> out = new HashMap<>(ids.size());
        userRepo.findAllById(ids).forEach(u -> out.put(u.getId(), toSummary(u)));
        return out;
    }

    private Map<UUID, PostByIdEntity> bulkLoadPosts(Set<UUID> postIds) {
        if (postIds.isEmpty()) return Map.of();
        Map<UUID, PostByIdEntity> out = new HashMap<>(postIds.size());
        postByIdRepo.findAllById(postIds).forEach(p -> out.put(p.getId(), p));
        return out;
    }

    private Map<UUID, String> bulkVideoUrls(Set<UUID> reelPostIds) {
        if (reelPostIds.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>(reelPostIds.size());
        bulkLoadPosts(reelPostIds).forEach((id, p) ->
                out.put(id, firstVideoUrl(p.getMediaUrls(), p.getMediaTypes())));
        return out;
    }

    // ── Live-row preview helpers (read-time hydration off posts_by_id) ───────

    /**
     * Truncated preview of the freshest text. Falls back to the snapshot the
     * row landed with when the canonical row is missing (deleted post still
     * inside a follower's feed_by_user TTL).
     */
    private static String livePreview(PostByIdEntity live, String snapshot) {
        if (live == null || live.getTextContent() == null) return snapshot;
        String t = live.getTextContent();
        return t.length() > 280 ? t.substring(0, 280) : t;
    }

    /** Freshest cover image (= first non-video media url); falls back to snapshot. */
    private static String liveCoverMedia(PostByIdEntity live, String snapshot) {
        if (live == null || live.getMediaUrls() == null || live.getMediaUrls().isEmpty()) return snapshot;
        return live.getMediaUrls().get(0);
    }

    /**
     * Freshest reel video URL (only meaningful for {@code postType == REEL}).
     * Computed from the same canonical row already loaded for preview / cover,
     * so no extra round-trip vs. the legacy {@link #bulkVideoUrls} path.
     */
    private static String liveVideoUrl(PostByIdEntity live, String postType) {
        if (!"REEL".equals(postType) || live == null) return null;
        return firstVideoUrl(live.getMediaUrls(), live.getMediaTypes());
    }

    private static String firstVideoUrl(List<String> urls, List<String> types) {
        if (urls == null || types == null) return null;
        for (int i = 0; i < Math.min(urls.size(), types.size()); i++) {
            if ("VIDEO".equalsIgnoreCase(types.get(i))) return urls.get(i);
        }
        return null;
    }

    private Map<UUID, PostCounterEntity> bulkLoadCounters(Set<UUID> postIds) {
        if (postIds.isEmpty()) return Map.of();
        Map<UUID, PostCounterEntity> out = new HashMap<>(postIds.size());
        for (PostCounterEntity c : postCounterRepo.findAllByPostIdIn(postIds)) {
            out.put(c.getPostId(), c);
        }
        return out;
    }

    private Map<UUID, CommentCounterEntity> bulkLoadCommentCounters(Set<UUID> commentIds) {
        if (commentIds.isEmpty()) return Map.of();
        Map<UUID, CommentCounterEntity> out = new HashMap<>(commentIds.size());
        for (CommentCounterEntity c : commentCounterRepo.findAllByCommentIdIn(commentIds)) {
            out.put(c.getCommentId(), c);
        }
        return out;
    }

    private Set<UUID> bulkLikedPosts(UUID viewerId, Set<UUID> postIds) {
        if (viewerId == null || postIds.isEmpty()) return Set.of();
        Set<UUID> liked = new HashSet<>();
        reactionRepo.findAllByPostIdInAndUserId(postIds, viewerId)
                .forEach(r -> liked.add(r.getPostId()));
        return liked;
    }

    private Set<UUID> bulkSavedPosts(UUID viewerId, Set<UUID> postIds) {
        if (viewerId == null || postIds.isEmpty()) return Set.of();
        Set<UUID> saved = new HashSet<>();
        saveRepo.findAllByPostIdInAndUserId(postIds, viewerId)
                .forEach(r -> saved.add(r.getPostId()));
        return saved;
    }

    private Set<UUID> bulkLikedComments(UUID viewerId, Set<UUID> commentIds) {
        if (viewerId == null || commentIds.isEmpty()) return Set.of();
        Set<UUID> liked = new HashSet<>();
        commentReactionRepo.findAllByCommentIdInAndUserId(commentIds, viewerId)
                .forEach(r -> liked.add(r.getCommentId()));
        return liked;
    }

    private static AuthorSummary toSummary(User u) {
        return new AuthorSummary(u.getId(), u.getUsername(), u.getFullName(), u.getProfileImage());
    }

    private static Instant toInstant(Object createdAt) {
        if (createdAt == null) return null;
        if (createdAt instanceof Instant i) return i;
        if (createdAt instanceof java.util.Date d) return d.toInstant();
        return null;
    }
}
