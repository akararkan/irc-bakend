package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.research.enums.ContributorRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Add or replace a single contributor on a research publication.
 *
 * <p>The referenced user must have role {@code RESEARCHER} or {@code SCHOLAR}.
 * Only the corresponding (owning) researcher may add contributors. Trying to
 * add a user who is already a contributor on the same research yields
 * {@code 409 CONFLICT}.</p>
 */
public record ContributorRequest(

        @NotNull(message = "userId is required")
        UUID userId,

        /** Defaults to CO_AUTHOR when omitted. */
        ContributorRole role,

        /** Optional ordering — lower value listed first. */
        Integer displayOrder,

        @Size(max = 500, message = "contributionNote must not exceed 500 characters")
        String contributionNote

) {
    public ContributorRequest {
        if (role == null) role = ContributorRole.CO_AUTHOR;
    }
}
