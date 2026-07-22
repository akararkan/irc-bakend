package ak.dev.irc.app.chat.dto.response;

import java.util.UUID;

/** Compact preview of the message a reply points at (rendered as a quoted stub). */
public record ReplyPreview(
        long messageId,
        UUID senderId,
        String type,
        String snippet,
        boolean deleted
) {}
