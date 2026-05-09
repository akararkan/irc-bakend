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
    private Integer watchedSeconds;

    private PostSummary post;
    private CommentSummary comment;

    /** Populated for GLOBAL_SEARCH / HASHTAG_SEARCH / MENTION_LOOKUP. */
    private String query;
    /** Comma-separated {@code SearchType}s for GLOBAL_SEARCH; null means "all". */
    private String searchScope;
    /** Number of hits returned by the search / mention lookup. */
    private Integer hitCount;

    /** Populated for MENTION_LOOKUP (clicked user) and PROFILE_VIEW. */
    private AuthorSummary targetUser;

    /** QnA references for QNA_* types. */
    private QuestionSummary question;
    private AnswerSummary answer;
    /** Reaction emoji for QNA_ANSWER_REACTION (string form to keep DTO independent). */
    private String qnaReactionType;

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

    @Data
    @Builder
    public static class QuestionSummary {
        private UUID id;
        private String title;
        private AuthorSummary author;
    }

    @Data
    @Builder
    public static class AnswerSummary {
        private UUID id;
        private UUID parentAnswerId;
        private String bodyPreview;
        private boolean accepted;
        private long bestAnswerVoteCount;
        private AuthorSummary author;
    }
}
