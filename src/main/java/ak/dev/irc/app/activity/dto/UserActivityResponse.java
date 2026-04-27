package ak.dev.irc.app.activity.dto;

import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.post.enums.PostType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class UserActivityResponse {

    private UUID id;
    private UserActivityType activityType;
    private PostReactionType reactionType;

    private PostSummary post;
    private CommentSummary comment;

    private LocalDateTime createdAt;
    private String timeAgo;
    private String formattedDate;

    @Data
    @Builder
    public static class PostSummary {
        private UUID id;
        private PostType postType;
        private String textPreview;
        private String thumbnailUrl;
        private AuthorSummary author;
    }

    @Data
    @Builder
    public static class CommentSummary {
        private UUID id;
        private String textPreview;
    }

    @Data
    @Builder
    public static class AuthorSummary {
        private UUID id;
        private String username;
        private String fullName;
        private String avatarUrl;
    }
}
