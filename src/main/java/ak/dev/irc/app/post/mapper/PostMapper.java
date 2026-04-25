package ak.dev.irc.app.post.mapper;



import ak.dev.irc.app.post.dto.CreatePostRequest;
import ak.dev.irc.app.post.dto.MediaItemRequest;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.dto.MediaItemResponse;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PostMapper {

    // ── Post ──────────────────────────────────────────────────

    public Post toEntity(CreatePostRequest req, User author) {
        Post post = Post.builder()
                .author(author)
                .postType(req.getPostType())
                .textContent(req.getTextContent())
                .visibility(req.getVisibility() != null ? req.getVisibility() : ak.dev.irc.app.post.enums.PostVisibility.PUBLIC)
                // voice fields removed for posts
                .audioTrackUrl(req.getAudioTrackUrl())
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
        return toResponse(post, null);
    }

    public PostResponse toResponse(Post post, ak.dev.irc.app.post.enums.PostReactionType myReaction) {
        PostResponse.AuthorSummary author = PostResponse.AuthorSummary.builder()
                .id(post.getAuthor().getId())
                .username(post.getAuthor().getUsername())
                .fullName(post.getAuthor().getFullName())
                .avatarUrl(post.getAuthor().getProfileImage())
                .role(post.getAuthor().getRole() != null ? post.getAuthor().getRole().name() : null)
                .build();

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
                .reactionCount(post.getReactionCount())
                .commentCount(post.getCommentCount())
                .shareCount(post.getShareCount())
                .viewCount(post.getViewCount())
                .myReaction(myReaction)
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }

    // ── Media ─────────────────────────────────────────────────

    public PostMedia toMediaEntity(MediaItemRequest req, Post post) {
        return PostMedia.builder()
                .post(post)
                .mediaType(req.getMediaType())
                .url(req.getUrl())
                .thumbnailUrl(req.getThumbnailUrl())
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
                .reactionCount(c.getReactionCount())
                .replyCount(c.getReplyCount())
                .myReaction(myReaction)
                .edited(c.isEdited())
                .editedAt(c.getEditedAt())
                .deleted(c.isDeleted())
                .deletedAt(c.getDeletedAt())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}