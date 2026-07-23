package ak.dev.irc.app.chat.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/** A channel's directory/detail view. Posting to and reading a channel use the
 *  normal conversation/message endpoints with this {@code id}. */
public record ChannelResponse(
        UUID id,
        String handle,
        String title,
        String description,
        boolean publicChannel,
        long subscriberCount,
        UUID ownerId,
        boolean subscribed,
        LocalDateTime createdAt
) {}
