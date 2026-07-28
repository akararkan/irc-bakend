package ak.dev.irc.app.chat.dto.response;

import java.util.UUID;

/**
 * One row of a live stream's "top supporters" leaderboard — a viewer and the
 * symbolic coins they have gifted into this stream. Ordered biggest-first by the
 * endpoint that returns it.
 */
public record GiftSupporter(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        long coins,
        int giftCount
) {}
