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
 * <p>Counter rows are point-reads on partition key (post_id / comment_id) and
 * cost ~1ms each; on a 20-item feed that's a 20ms overhead, well worth eliminating
 * client-side N+1.</p>
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
        return new PostResponse(
                p.getId(),
                p.getAuthorId(),
                authorOf(p.getAuthorId()),
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
        return rows.stream()
                .map(r -> {
                    PostByIdEntity post = postByIdRepo.findById(r.getPostId()).orElse(null);
                    if (post == null) return null;
                    return hydrate(post, r.getCreatedAt(), r.getCollectionName());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ── feed hydration (bulk) ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FeedItemResponse> hydrateHomeFeed(List<FeedByUserEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoad(rows, FeedByUserEntity::getAuthorId);
        UUID viewerId = currentViewerId();
        return rows.stream()
                .map(r -> {
                    PostCounterEntity c = postCounterRepo.findByPostId(r.getPostId()).orElse(null);
                    return new FeedItemResponse(
                            r.getPostId(),
                            r.getAuthorId(),
                            authors.get(r.getAuthorId()),
                            r.getPostType(),
                            r.getTextPreview(),
                            r.getMediaUrl(),
                            nullSafe(c == null ? null : c.getReactionCount()),
                            nullSafe(c == null ? null : c.getCommentCount()),
                            nullSafe(c == null ? null : c.getViewCount()),
                            nullSafe(c == null ? null : c.getSaveCount()),
                            nullSafe(c == null ? null : c.getShareCount()),
                            viewerId != null && reactionRepo.find(r.getPostId(), viewerId).isPresent(),
                            viewerId != null && saveRepo.find(r.getPostId(), viewerId).isPresent(),
                            r.getCreatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedItemResponse> hydrateProfileFeed(List<PostByAuthorEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoad(rows, PostByAuthorEntity::getAuthorId);
        UUID viewerId = currentViewerId();
        return rows.stream()
                .map(r -> {
                    PostCounterEntity c = postCounterRepo.findByPostId(r.getPostId()).orElse(null);
                    return new FeedItemResponse(
                            r.getPostId(),
                            r.getAuthorId(),
                            authors.get(r.getAuthorId()),
                            r.getPostType(),
                            r.getTextPreview(),
                            r.getMediaUrl(),
                            nullSafe(c == null ? null : c.getReactionCount()),
                            nullSafe(c == null ? null : c.getCommentCount()),
                            nullSafe(c == null ? null : c.getViewCount()),
                            nullSafe(c == null ? null : c.getSaveCount()),
                            nullSafe(c == null ? null : c.getShareCount()),
                            viewerId != null && reactionRepo.find(r.getPostId(), viewerId).isPresent(),
                            viewerId != null && saveRepo.find(r.getPostId(), viewerId).isPresent(),
                            toInstant(r.getCreatedAt()));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedItemResponse> hydrateReels(List<ReelsByDayEntity> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<UUID, AuthorSummary> authors = bulkLoad(rows, ReelsByDayEntity::getAuthorId);
        UUID viewerId = currentViewerId();
        return rows.stream()
                .map(r -> {
                    PostCounterEntity c = postCounterRepo.findByPostId(r.getPostId()).orElse(null);
                    return new FeedItemResponse(
                            r.getPostId(),
                            r.getAuthorId(),
                            authors.get(r.getAuthorId()),
                            "REEL",
                            r.getTextPreview(),
                            r.getMediaUrl(),
                            nullSafe(c == null ? null : c.getReactionCount()),
                            nullSafe(c == null ? null : c.getCommentCount()),
                            nullSafe(c == null ? null : c.getViewCount()),
                            nullSafe(c == null ? null : c.getSaveCount()),
                            nullSafe(c == null ? null : c.getShareCount()),
                            viewerId != null && reactionRepo.find(r.getPostId(), viewerId).isPresent(),
                            viewerId != null && saveRepo.find(r.getPostId(), viewerId).isPresent(),
                            toInstant(r.getCreatedAt()));
                })
                .toList();
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
        Map<UUID, AuthorSummary> authors = bulkLoad(rows, CommentByPostEntity::getAuthorId);
        UUID viewerId = currentViewerId();
        return rows.stream()
                .map(c -> {
                    CommentCounterEntity counters =
                            commentCounterRepo.findByCommentId(c.getCommentId()).orElse(null);
                    return new CommentResponse(
                            c.getCommentId(),
                            c.getPostId(),
                            c.getAuthorId(),
                            authors.get(c.getAuthorId()),
                            c.getTextContent(),
                            c.getMediaUrl(),
                            c.getMediaType(),
                            nullSafe(counters == null ? null : counters.getReactionCount()),
                            nullSafe(counters == null ? null : counters.getReplyCount()),
                            viewerId != null && commentReactionRepo.find(c.getCommentId(), viewerId).isPresent(),
                            c.getDeleted(),
                            c.getEdited(),
                            c.getCreatedAt());
                })
                .toList();
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
        Map<UUID, AuthorSummary> authors = bulkLoad(rows, ReplyByCommentEntity::getAuthorId);
        UUID viewerId = currentViewerId();
        return rows.stream()
                .map(r -> {
                    CommentCounterEntity counters =
                            commentCounterRepo.findByCommentId(r.getReplyId()).orElse(null);
                    return new ReplyResponse(
                            r.getReplyId(),
                            r.getParentId(),
                            r.getPostId(),
                            r.getAuthorId(),
                            authors.get(r.getAuthorId()),
                            r.getTextContent(),
                            r.getMediaUrl(),
                            nullSafe(counters == null ? null : counters.getReactionCount()),
                            viewerId != null && commentReactionRepo.find(r.getReplyId(), viewerId).isPresent(),
                            r.getDeleted(),
                            r.getEdited(),
                            r.getCreatedAt());
                })
                .toList();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static long nullSafe(Long v) { return v == null ? 0L : v; }

    private UUID currentViewerId() {
        return SecurityUtils.getCurrentUserId().orElse(null);
    }

    private AuthorSummary authorOf(UUID userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(PostHydrator::toSummary).orElse(null);
    }

    private <T> Map<UUID, AuthorSummary> bulkLoad(List<T> rows, Function<T, UUID> idFn) {
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
