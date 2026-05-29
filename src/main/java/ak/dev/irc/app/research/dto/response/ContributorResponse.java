package ak.dev.irc.app.research.dto.response;

import ak.dev.irc.app.research.enums.ContributorRole;
import ak.dev.irc.app.user.enums.Role;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One contributor row, ready for the UI's author list / acknowledgements panel.
 */
public record ContributorResponse(

        UUID id,

        // ── User snapshot ────────────────────────────────────────────────
        UUID userId,
        String fullName,
        String username,
        String profileImage,
        Role userRole,

        // ── Contribution metadata ────────────────────────────────────────
        ContributorRole role,
        Integer displayOrder,
        String contributionNote,

        LocalDateTime addedAt

) {}
