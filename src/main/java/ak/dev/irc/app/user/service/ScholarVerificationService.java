package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.request.ScholarVerificationRequest;
import ak.dev.irc.app.user.dto.request.VerificationReviewRequest;
import ak.dev.irc.app.user.dto.response.ScholarVerificationResponse;
import ak.dev.irc.app.user.enums.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ScholarVerificationService {

    ScholarVerificationResponse apply(ScholarVerificationRequest request);

    ScholarVerificationResponse getMyStatus();

    Page<ScholarVerificationResponse> getQueue(VerificationStatus status, Pageable pageable);

    ScholarVerificationResponse approve(UUID applicationId, VerificationReviewRequest request);

    ScholarVerificationResponse reject(UUID applicationId, VerificationReviewRequest request);
}
