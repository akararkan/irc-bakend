package ak.dev.irc.app.user.dto.request;

import jakarta.validation.constraints.Size;

public record VerificationReviewRequest(
    @Size(max = 1000) String reviewerNote
) {}
