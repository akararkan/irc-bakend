package ak.dev.irc.app.research.enums;

/**
 * Role of a non-owner participant on a research publication.
 *
 * <p>The {@code researcher} field on {@link ak.dev.irc.app.research.entity.Research}
 * is the corresponding (owning) author — contributors are additional named
 * participants the owner attaches to the paper.</p>
 */
public enum ContributorRole {

    /** Listed as an author alongside the corresponding author. */
    CO_AUTHOR,

    /** Supervised or directed the research. */
    ADVISOR,

    /** Provided peer review on the manuscript. */
    REVIEWER,

    /** Performed translation work on the published text. */
    TRANSLATOR,

    /** Edited the manuscript. */
    EDITOR,

    /** Acknowledged contributor (data, funding, support). */
    CONTRIBUTOR
}
