package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.request.VerificationReviewRequest;
import ak.dev.irc.app.user.dto.response.ScholarVerificationResponse;
import ak.dev.irc.app.user.entity.ScholarVerification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.AccountType;
import ak.dev.irc.app.user.enums.VerificationStatus;
import ak.dev.irc.app.user.enums.VerificationTier;
import ak.dev.irc.app.user.repository.ScholarVerificationRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.ScholarVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScholarVerificationServiceImpl implements ScholarVerificationService {

    private final UserRepository                 userRepository;
    private final ScholarVerificationRepository  verificationRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<ScholarVerificationResponse> getQueue(VerificationStatus status, Pageable pageable) {
        return verificationRepository.findByStatus(status, pageable).map(this::toResponse);
    }

    @Override
    public ScholarVerificationResponse approve(UUID applicationId, VerificationReviewRequest req) {
        UUID reviewerId = authenticatedUserId();
        User reviewer   = findActiveOrThrow(reviewerId);

        ScholarVerification app = verificationRepository.findById(applicationId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "ScholarVerification", "id", applicationId));

        if (app.getStatus() != VerificationStatus.PENDING
         && app.getStatus() != VerificationStatus.UNDER_REVIEW) {
            throw new BadRequestException(
                "Application is not in a reviewable state.", "NOT_REVIEWABLE");
        }

        app.setStatus(VerificationStatus.APPROVED);
        app.setReviewedBy(reviewer);
        app.setReviewerNote(req != null ? req.reviewerNote() : null);
        app.setReviewedAt(LocalDateTime.now());
        app.audit(AuditAction.UPDATE, "Verification approved by " + reviewer.getUsername());
        verificationRepository.save(app);

        // Promote the user
        User applicant = app.getUser();
        applicant.setVerificationTier(app.getClaimedTier());
        applicant.setAccountType(
            app.getClaimedTier() == VerificationTier.SENIOR_SCHOLAR
                || app.getClaimedTier() == VerificationTier.SCHOLAR
                ? AccountType.VERIFIED_SCHOLAR
                : AccountType.VERIFIED_RESEARCHER
        );
        if (app.getOrcidId() != null) applicant.setOrcidId(app.getOrcidId());
        applicant.audit(AuditAction.UPDATE, "Promoted via verification approval");
        userRepository.save(applicant);

        log.info("User [{}] verification approved — tier={} by reviewer [{}]",
            applicant.getId(), app.getClaimedTier(), reviewerId);

        return toResponse(app);
    }

    @Override
    public ScholarVerificationResponse reject(UUID applicationId, VerificationReviewRequest req) {
        UUID reviewerId = authenticatedUserId();
        User reviewer   = findActiveOrThrow(reviewerId);

        ScholarVerification app = verificationRepository.findById(applicationId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "ScholarVerification", "id", applicationId));

        if (app.getStatus() != VerificationStatus.PENDING
         && app.getStatus() != VerificationStatus.UNDER_REVIEW) {
            throw new BadRequestException(
                "Application is not in a reviewable state.", "NOT_REVIEWABLE");
        }

        app.setStatus(VerificationStatus.REJECTED);
        app.setReviewedBy(reviewer);
        app.setReviewerNote(req != null ? req.reviewerNote() : null);
        app.setReviewedAt(LocalDateTime.now());
        app.audit(AuditAction.UPDATE, "Verification rejected by " + reviewer.getUsername());
        verificationRepository.save(app);

        log.info("User [{}] verification rejected by reviewer [{}]",
            app.getUser().getId(), reviewerId);

        return toResponse(app);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ScholarVerificationResponse toResponse(ScholarVerification v) {
        return new ScholarVerificationResponse(
            v.getId(),
            v.getUser().getId(),
            v.getUser().getUsername(),
            v.getClaimedTier(),
            v.getAffiliation(),
            v.getEvidenceUrls(),
            v.getOrcidId(),
            v.getStatus(),
            v.getReviewedBy() != null ? v.getReviewedBy().getId() : null,
            v.getReviewedBy() != null ? v.getReviewedBy().getUsername() : null,
            v.getReviewerNote(),
            v.getReviewedAt(),
            v.getCreatedAt()
        );
    }

    private User findActiveOrThrow(UUID id) {
        return userRepository.findActiveById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
            .orElseThrow(() -> new UnauthorizedException(
                "You must be authenticated to perform this action."));
    }
}
