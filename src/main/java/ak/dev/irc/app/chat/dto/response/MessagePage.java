package ak.dev.irc.app.chat.dto.response;

import java.util.List;

/**
 * Cursor page for the Cassandra-backed message log. The cursor is the Snowflake
 * {@code messageId} of the oldest row in the page — pass it back as {@code cursor}
 * to load the next-older page. {@code null} nextCursor = start of history reached.
 */
public record MessagePage<T>(
        List<T> items,
        Long nextCursor,
        boolean hasMore
) {}
