package ak.dev.irc.app.user.dto.request;

import ak.dev.irc.app.user.enums.VerificationTier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScholarVerificationRequest(
    @NotNull VerificationTier claimedTier,
    @Size(max = 200) String   affiliation,
    String                    evidenceUrls,
    @Size(max = 20)  String   orcidId
) {}
