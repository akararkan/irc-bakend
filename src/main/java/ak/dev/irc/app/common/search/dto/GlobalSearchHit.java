package ak.dev.irc.app.common.search.dto;

import java.util.UUID;

/**
 * One entry in a global multi-entity search result.
 *
 * @param type  POST / REEL / QUESTION / RESEARCH — the frontend uses this
 *              to pick which hydration endpoint to call.
 * @param id    canonical UUID of the underlying entity
 * @param score raw BM25 relevance score (higher = better match)
 */
public record GlobalSearchHit(String type, UUID id, double score) {}
