package ak.dev.irc.app.post.mapper;



import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.post.dto.CreatePostRequest;
import ak.dev.irc.app.post.dto.MediaItemRequest;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.dto.MediaItemResponse;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PostMapper {

    private final CounterCache counterCache;

    private static long nz(Long v) { return v == null ? 0L : v; }

    // ── Post ──────────────────────────────────────────────────

    public Post toEntity(CreatePostRequest req, User author) {
        Post post = Post.builder()
                .author(author)
                .postType(req.getPostType())
                .textContent(req.getTextContent())
                .visibility(req.getVisibility() != null ? req.getVisibility() : ak.dev.irc.app.post.enums.PostVisibility.PUBLIC)
                // voice fields removed for posts
                .audioTrackUrl(req.getAudioTrackUrl())
                .audioTrackS3Key(req.getAudioTrackS3Key())
                .audioTrackName(req.getAudioTrackName())
                .locationName(req.getLocationName())
                .locationLat(req.getLocationLat())
                .locationLng(req.getLocationLng())
                .build();

        if (req.getMediaList() != null) {
            List<PostMedia> mediaEntities = req.getMediaList().stream()
                    .map(m -> toMediaEntity(m, post))
                    .collect(Collectors.toList());
            post.getMediaList().addAll(mediaEntities);
        }

        return post;
    }

    public PostResponse toResponse(Post post) {
        return toResponse(post, null, false);
    }

    public PostResponse toResponse(Post post, ak.dev.irc.app.post.enums.PostReactionType myReaction) {
        return toResponse(post, myReaction, false);
    }

    public PostResponse toResponse(Post post,
                                   ak.dev.irc.app.post.enums.PostReactionType myReaction,
                                   boolean isSaved) {
        PostResponse.AuthorSummary author = PostResponse.AuthorSummary.builder()
                .id(post.getAuthor().getId())
                .username(post.getAuthor().getUsername())
                .fullName(post.getAuthor().getFullName())
                .avatarUrl(post.getAuthor().getProfileImage())
                .role(post.getAuthor().getRole() != null ? post.getAuthor().getRole().name() : null)
                .build();

        boolean isRepost = post.getPostType() == PostType.REPOST && post.getSharedPost() != null;

        return PostResponse.builder()
                .id(post.getId())
                .author(author)
                .textContent(post.getTextContent())
                .postType(post.getPostType())
                .status(post.getStatus())
                .visibility(post.getVisibility())
                // voice fields removed for posts
                .audioTrackUrl(post.getAudioTrackUrl())
                .audioTrackName(post.getAudioTrackName())
                .mediaList(post.getMediaList() == null ? Collections.emptyList()
                        : post.getMediaList().stream().map(this::toMediaResponse).collect(Collectors.toList()))
                .locationName(post.getLocationName())
                .locationLat(post.getLocationLat())
                .locationLng(post.getLocationLng())
                .sharedPost(post.getSharedPost() != null ? toResponse(post.getSharedPost()) : null)
                .shareLink(post.getShareLink())
                .isRepost(isRepost)
                // Counters read from Redis with DB fallback. Cache hit = sub-ms;
                // cache miss = one Postgres column read which then warms Redis.
                .reactionCount(counterCache.getOr(CounterCache.Kind.POST, post.getId(),
                        CounterCache.F_REACTIONS, () -> nz(post.getReactionCount())))
                .commentCount(counterCache.getOr(CounterCache.Kind.POST, post.getId(),
                        CounterCache.F_COMMENTS, () -> nz(post.getCommentCount())))
                .shareCount(counterCache.getOr(CounterCache.Kind.POST, post.getId(),
                        CounterCache.F_SHARES, () -> nz(post.getShareCount())))
                .viewCount(counterCache.getOr(CounterCache.Kind.POST, post.getId(),
                        CounterCache.F_VIEWS, () -> nz(post.getViewCount())))
                .saveCount(counterCache.getOr(CounterCache.Kind.POST, post.getId(),
                        CounterCache.F_SAVES, () -> nz(post.getSaveCount())))
                .myReaction(myReaction)
                .isSaved(isSaved)
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .timeAgo(TimeDisplayUtil.timeAgo(post.getCreatedAt()))
                .formattedDate(TimeDisplayUtil.formattedDate(post.getCreatedAt()))
                .build();
    }

    // ── Media ─────────────────────────────────────────────────

    public PostMedia toMediaEntity(MediaItemRequest req, Post post) {
        return PostMedia.builder()
                .post(post)
                .mediaType(req.getMediaType())
                .url(req.getUrl())
                .s3Key(req.getS3Key())
                .thumbnailUrl(req.getThumbnailUrl())
                .thumbnailS3Key(req.getThumbnailS3Key())
                .altText(req.getAltText())
                .durationSeconds(req.getDurationSeconds())
                .fileSizeBytes(req.getFileSizeBytes())
                .mimeType(req.getMimeType())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
    }

    public MediaItemResponse toMediaResponse(PostMedia m) {
        return MediaItemResponse.builder()
                .id(m.getId())
                .mediaType(m.getMediaType())
                .url(m.getUrl())
                .thumbnailUrl(m.getThumbnailUrl())
                .altText(m.getAltText())
                .durationSeconds(m.getDurationSeconds())
                .fileSizeBytes(m.getFileSizeBytes())
                .mimeType(m.getMimeType())
                .sortOrder(m.getSortOrder())
                .build();
    }

    // ── Comment ───────────────────────────────────────────────

    public CommentResponse toCommentResponse(PostComment c) {
        return toCommentResponse(c, null);
    }

    public CommentResponse toCommentResponse(PostComment c,
                                             ak.dev.irc.app.post.enums.PostReactionType myReaction) {
        PostResponse.AuthorSummary author = PostResponse.AuthorSummary.builder()
                .id(c.getAuthor().getId())
                .username(c.getAuthor().getUsername())
                .fullName(c.getAuthor().getFullName())
                .avatarUrl(c.getAuthor().getProfileImage())
                .build();

        return CommentResponse.builder()
                .id(c.getId())
                .postId(c.getPost().getId())
                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                .author(author)
                .textContent(c.isDeleted() ? null : c.getTextContent())
                .mediaUrl(c.isDeleted() ? null : c.getMediaUrl())
                .mediaType(c.isDeleted() ? null : c.getMediaType())
                .mediaThumbnailUrl(c.isDeleted() ? null : c.getMediaThumbnailUrl())
                // voice/comment audio removed for posts
                .reactionCount(counterCache.getOr(CounterCache.Kind.POST_COMMENT, c.getId(),
                        CounterCache.F_REACTIONS, () -> nz(c.getReactionCount())))
                .replyCount(counterCache.getOr(CounterCache.Kind.POST_COMMENT, c.getId(),
                        CounterCache.F_REPLIES, () -> nz(c.getReplyCount())))
                .myReaction(myReaction)
                .edited(c.isEdited())
                .editedAt(c.getEditedAt())
                .deleted(c.isDeleted())
                .deletedAt(c.getDeletedAt())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .timeAgo(TimeDisplayUtil.timeAgo(c.getCreatedAt()))
                .formattedDate(TimeDisplayUtil.formattedDate(c.getCreatedAt()))
                .build();
    }
}
