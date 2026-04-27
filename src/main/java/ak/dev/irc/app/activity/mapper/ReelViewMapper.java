package ak.dev.irc.app.activity.mapper;

import ak.dev.irc.app.activity.dto.ReelViewResponse;
import ak.dev.irc.app.activity.entity.ReelView;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostMedia;
import ak.dev.irc.app.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReelViewMapper {

    private static final int TEXT_PREVIEW_LIMIT = 140;

    public ReelViewResponse toResponse(ReelView v) {
        return ReelViewResponse.builder()
                .id(v.getId())
                .watchedSeconds(v.getWatchedSeconds())
                .reel(toReelSummary(v.getPost()))
                .watchedAt(v.getCreatedAt())
                .timeAgo(TimeDisplayUtil.timeAgo(v.getCreatedAt()))
                .formattedDate(TimeDisplayUtil.formattedDate(v.getCreatedAt()))
                .build();
    }

    private ReelViewResponse.ReelSummary toReelSummary(Post post) {
        if (post == null) return null;
        PostMedia firstMedia = (post.getMediaList() != null && !post.getMediaList().isEmpty())
                ? post.getMediaList().get(0)
                : null;

        return ReelViewResponse.ReelSummary.builder()
                .id(post.getId())
                .textPreview(truncate(post.getTextContent()))
                .thumbnailUrl(firstMedia != null ? firstMedia.getThumbnailUrl() : null)
                .mediaUrl(firstMedia != null ? firstMedia.getUrl() : null)
                .durationSeconds(firstMedia != null ? firstMedia.getDurationSeconds() : null)
                .author(toAuthorSummary(post.getAuthor()))
                .build();
    }

    private ReelViewResponse.AuthorSummary toAuthorSummary(User u) {
        if (u == null) return null;
        return ReelViewResponse.AuthorSummary.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .avatarUrl(u.getProfileImage())
                .build();
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() > TEXT_PREVIEW_LIMIT
                ? text.substring(0, TEXT_PREVIEW_LIMIT) + "…"
                : text;
    }
}
