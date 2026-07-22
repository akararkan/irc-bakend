package ak.dev.irc.app.chat.dto.response;

/** Aggregated reaction bucket for a message. */
public record ReactionSummary(
        String emoji,
        long count,
        boolean reactedByMe
) {}
