package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Hydrated "People You May Know" entry — candidate identity + why they are
 * suggested, ready to render without a second fetch.
 * {@code score} is the engine's composite score (higher = stronger match);
 * {@code reason} is the human-readable top-signals label
 * (e.g. "4 mutual follows · in your contacts · same institution").
 */
public record SuggestionResponse(
        UUID    candidateId,
        AuthorSummary candidate,
        double  score,
        String  reason,
        Instant computedAt
) {}
