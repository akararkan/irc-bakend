package ak.dev.irc.app.user.dto.response;

import ak.dev.irc.app.user.enums.VerificationStatus;
import ak.dev.irc.app.user.enums.VerificationTier;

import java.time.LocalDateTime;
import java.util.UUID;

public record ScholarVerificationResponse(
    UUID               id,
    UUID               userId,
    String             username,
    VerificationTier   claimedTier,
    String             affiliation,
    String             evidenceUrls,
    String             orcidId,
    VerificationStatus status,
    UUID               reviewedById,
    String             reviewedByUsername,
    String             reviewerNote,
    LocalDateTime      reviewedAt,
    LocalDateTime      createdAt
) {}
