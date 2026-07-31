package ak.dev.irc.app.common.search.dto;

/**
 * Result envelope shared by the admin reindex endpoints for the
 * user / channel / answer / sound indices ({@code SearchAdminController}).
 * Mirrors the shape of {@code ResearchSearchService.ReindexResult} so every
 * reindex endpoint returns the same JSON contract.
 *
 * @param indexDropped     whether the index was deleted and recreated first
 * @param documentsIndexed rows successfully written to ES
 * @param pages            source pages walked
 * @param durationMs       wall-clock duration of the run
 * @param note             human-readable summary (partial-failure detail lands here)
 */
public record ReindexSummary(boolean indexDropped,
                             long    documentsIndexed,
                             int     pages,
                             long    durationMs,
                             String  note) {}
