package ak.dev.irc.app.common.search;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Multi-corpus search response — one bucket per {@link SearchType}.
 *
 * <p>{@code total} carries an approximate count per bucket (the exact-count
 * query is intentionally skipped at billion-row scale; clients render
 * "100+" once the limit cap is hit).</p>
 */
@Data
@Builder
public class UnifiedSearchResult {

    private String query;

    /** Hits per requested type — empty list when nothing matched. */
    private Map<SearchType, List<SearchHit>> buckets;

    /** Total milliseconds spent across all corpora. Useful for client telemetry. */
    private long elapsedMs;
}
