package ak.dev.irc.app.post.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cursor-paginated response. The client passes {@code nextCursor} back as the
 * {@code cursor} query parameter on the following page; a null {@code nextCursor}
 * means the end of the feed.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CursorPage<T> {
    private List<T> items;
    private LocalDateTime nextCursor;
    private boolean hasMore;
}
