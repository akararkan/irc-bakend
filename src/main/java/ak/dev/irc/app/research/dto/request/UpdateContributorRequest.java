package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.common.messages.ResearchMessages;
import ak.dev.irc.app.research.enums.ContributorRole;
import jakarta.validation.constraints.Size;

/**
 * Partial update of an existing contributor row. All fields optional —
 * any non-null value overwrites the current one.
 */
public record UpdateContributorRequest(

        ContributorRole role,

        Integer displayOrder,

        @Size(max = 500, message = ResearchMessages.VAL_CONTRIBUTION_NOTE_MAX_500)
        String contributionNote

) {}
