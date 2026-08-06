package ak.dev.irc.app.admin.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Keyset page envelope for Cassandra-backed admin lists — mirrors the
 * {@code cursor}/{@code pageSize} contract {@code AuditLogController} set.
 * {@code nextCursor} is {@code null} on the last page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
        return new CursorPage<>(items, nextCursor);
    }

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }
}
