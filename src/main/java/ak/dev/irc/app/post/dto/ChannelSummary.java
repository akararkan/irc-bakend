package ak.dev.irc.app.post.dto;

import java.util.UUID;

/**
 * Compact channel identity carried on {@code CHANNEL_POST} feed items —
 * enough for the card header (name + avatar + verified badge) and the
 * click-through to {@code /api/v1/channels/{id}} without another fetch.
 */
public record ChannelSummary(
        UUID    id,
        String  handle,
        String  title,
        String  avatarUrl,
        boolean verified,
        int     subscriberCount
) {}
