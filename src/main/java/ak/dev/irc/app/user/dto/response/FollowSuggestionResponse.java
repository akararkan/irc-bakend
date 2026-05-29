package ak.dev.irc.app.user.dto.response;

import ak.dev.irc.app.user.enums.Role;

import java.util.UUID;

/**
 * Hydrated follow suggestion — carries enough data for the UI to render the
 * suggestion card without any follow-up requests.
 *
 * Used by both the friends-of-friends endpoint and the who-to-follow endpoint.
 */
public record FollowSuggestionResponse(
        UUID    id,
        String  username,
        String  displayName,
        String  avatarUrl,
        long    followerCount,
        Role    role,           // USER / RESEARCHER / SCHOLAR — drives the badge on the card
        boolean isFollowing,
        int     mutualCount,    // 0 for who-to-follow results (no mutual signal)
        String  reason          // human-readable: "3 mutual follows" / "Verified Scholar"
) {}
