package ak.dev.irc.app.common.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a global multi-entity search result.
 *
 * <p><strong>Shape alignment with {@code TagController.TaggedContent}.</strong>
 * Both response shapes now use {@code contentType} / {@code contentId} as the
 * canonical field names. {@link #type} / {@link #id} are kept as deprecated
 * aliases for one release window so existing frontends keep working — they
 * carry the same values; new code should consume {@code contentType} /
 * {@code contentId}.</p>
 *
 * <p>The optional {@code brief.*} fields are populated when the caller passes
 * {@code expand=brief} (or doesn't opt out via {@code fields=hits}). They
 * mirror what's already in the ES document, so populating them costs zero
 * extra round-trips and avoids the typeahead N+1 on the client.</p>
 *
 * @param contentType     POST / REEL / QUESTION / RESEARCH / ANSWER / USER / CHANNEL / SOUND
 * @param contentId       canonical UUID of the underlying entity
 * @param type            <em>deprecated alias for {@link #contentType}</em>
 * @param id              <em>deprecated alias for {@link #contentId}</em>
 * @param parentId        ANSWER hits only: the question this answer belongs to
 *                        (deep-link target); null for every other type
 * @param score           raw BM25 relevance score (higher = better)
 * @param titlePreview    first ~280 chars of the entity's primary text (null if unknown)
 * @param authorUsername  handle of the author/researcher — for USER hits the
 *                        user's own username, for CHANNEL hits the channel @handle
 * @param authorName      display name — for SOUND hits the artist name
 * @param createdAt       entity creation time (null if unknown)
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalSearchHit(
        String  contentType,
        UUID    contentId,
        String  type,           // deprecated alias of contentType (see class javadoc)
        UUID    id,             // deprecated alias of contentId   (see class javadoc)
        UUID    parentId,       // ANSWER → owning question id (see class javadoc)
        double  score,
        String  titlePreview,
        String  authorUsername,
        String  authorName,
        Instant createdAt) {

    /**
     * Compact constructor used by the legacy 3-arg call sites still in tests.
     * Auto-fills the deprecated aliases so callers don't have to repeat themselves.
     */
    public static GlobalSearchHit minimal(String contentType, UUID contentId, double score) {
        return GlobalSearchHit.builder()
                .contentType(contentType).contentId(contentId)
                .type(contentType).id(contentId)
                .score(score)
                .build();
    }
}
