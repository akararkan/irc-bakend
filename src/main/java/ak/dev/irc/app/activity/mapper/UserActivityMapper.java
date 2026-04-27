package ak.dev.irc.app.activity.mapper;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.entity.UserActivity;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostComment;
import ak.dev.irc.app.post.entity.PostMedia;
import ak.dev.irc.app.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UserActivityMapper {

    private static final int TEXT_PREVIEW_LIMIT = 140;

    public UserActivityResponse toResponse(UserActivity a) {
        return UserActivityResponse.builder()
                .id(a.getId())
                .activityType(a.getActivityType())
                .reactionType(a.getReactionType())
                .post(toPostSummary(a.getPost()))
                .comment(toCommentSummary(a.getComment()))
                .createdAt(a.getCreatedAt())
                .timeAgo(TimeDisplayUtil.timeAgo(a.getCreatedAt()))
                .formattedDate(TimeDisplayUtil.formattedDate(a.getCreatedAt()))
                .build();
    }

    private UserActivityResponse.PostSummary toPostSummary(Post post) {
        if (post == null) return null;
        return UserActivityResponse.PostSummary.builder()
                .id(post.getId())
                .postType(post.getPostType())
                .textPreview(truncate(post.getTextContent()))
                .thumbnailUrl(firstThumbnail(post.getMediaList()))
                .author(toAuthorSummary(post.getAuthor()))
                .build();
    }

    private UserActivityResponse.CommentSummary toCommentSummary(PostComment c) {
        if (c == null) return null;
        return UserActivityResponse.CommentSummary.builder()
                .id(c.getId())
                .textPreview(c.isDeleted() ? null : truncate(c.getTextContent()))
                .build();
    }

    private UserActivityResponse.AuthorSummary toAuthorSummary(User u) {
        if (u == null) return null;
        return UserActivityResponse.AuthorSummary.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .avatarUrl(u.getProfileImage())
                .build();
    }

    private String firstThumbnail(List<PostMedia> media) {
        if (media == null || media.isEmpty()) return null;
        PostMedia first = media.get(0);
        return first.getThumbnailUrl() != null ? first.getThumbnailUrl() : first.getUrl();
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() > TEXT_PREVIEW_LIMIT
                ? text.substring(0, TEXT_PREVIEW_LIMIT) + "…"
                : text;
    }
}
