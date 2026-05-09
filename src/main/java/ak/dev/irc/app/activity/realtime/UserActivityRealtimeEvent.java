package ak.dev.irc.app.activity.realtime;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire payload broadcast on a user's activity channel. Single shape so the
 * client can dispatch on {@link #activityType} and read whichever optional
 * fields are populated for that type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserActivityRealtimeEvent {

    private UUID activityId;
    private UUID userId;
    private UserActivityType activityType;

    private UUID postId;
    private UUID commentId;
    private UUID targetUserId;

    private String query;
    private String searchScope;
    private Integer hitCount;

    private String reactionType;
    private Integer watchedSeconds;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /** Compact extra payload mirroring {@link UserActivityResponse} for richer rendering. */
    private UserActivityResponse activity;

    public static UserActivityRealtimeEvent from(UserActivityResponse a) {
        if (a == null) return null;
        return UserActivityRealtimeEvent.builder()
                .activityId(a.getId())
                .activityType(a.getActivityType())
                .query(a.getQuery())
                .searchScope(a.getSearchScope())
                .hitCount(a.getHitCount())
                .reactionType(a.getReactionType() != null ? a.getReactionType().name() : null)
                .watchedSeconds(a.getWatchedSeconds())
                .postId(a.getPost() != null ? a.getPost().getId() : null)
                .commentId(a.getComment() != null ? a.getComment().getId() : null)
                .targetUserId(a.getTargetUser() != null ? a.getTargetUser().getId() : null)
                .timestamp(a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.now())
                .activity(a)
                .build();
    }
}
