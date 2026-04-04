package ak.dev.irc.app.user.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Returned by every social action (follow / unfollow / block / unblock / restrict / unrestrict).
 * Carries the updated social status so the frontend can refresh its UI without an extra API call.
 */
public record SocialActionResponse(

        /** e.g. "FOLLOWED", "UNFOLLOWED", "BLOCKED", "UNBLOCKED", "RESTRICTED", "UNRESTRICTED" */
        String action,

        UUID   targetId,
        String targetUsername,
        String targetProfileImage,

        /** The relationship state after this action was applied */
        SocialStatusResponse updatedStatus,

        LocalDateTime performedAt
) {
    public static SocialActionResponse of(String action,
                                          UUID targetId,
                                          String targetUsername,
                                          String targetProfileImage,
                                          SocialStatusResponse updatedStatus) {
        return new SocialActionResponse(
                action, targetId, targetUsername, targetProfileImage,
                updatedStatus, LocalDateTime.now()
        );
    }
}

