package ak.dev.irc.app.user.dto.response;

public record SocialStatusResponse(
        boolean isFollowing,
        boolean isBlocking,
        boolean isRestricting,
        boolean isBlockedByThem,
        long    followerCount,
        long    followingCount
) {}
