package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.VerificationReviewRequest;
import ak.dev.irc.app.user.dto.response.ScholarVerificationResponse;
import ak.dev.irc.app.user.enums.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Admin-only management of legacy scholar verification applications.
 * Users may no longer create applications; account types are changed
 * directly by admins.
 */
public interface ScholarVerificationService {

    Page<ScholarVerificationResponse> getQueue(VerificationStatus status, Pageable pageable);

    ScholarVerificationResponse approve(UUID applicationId, VerificationReviewRequest request);

    ScholarVerificationResponse reject(UUID applicationId, VerificationReviewRequest request);
}
