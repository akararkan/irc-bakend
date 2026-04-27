package ak.dev.irc.app.activity.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ReelViewResponse {

    private UUID id;
    private Integer watchedSeconds;

    private ReelSummary reel;

    private LocalDateTime watchedAt;
    private String timeAgo;
    private String formattedDate;

    @Data
    @Builder
    public static class ReelSummary {
        private UUID id;
        private String textPreview;
        private String thumbnailUrl;
        private String mediaUrl;
        private Integer durationSeconds;
        private AuthorSummary author;
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
