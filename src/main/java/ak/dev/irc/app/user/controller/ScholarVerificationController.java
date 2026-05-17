package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.ScholarVerificationRequest;
import ak.dev.irc.app.user.dto.request.VerificationReviewRequest;
import ak.dev.irc.app.user.dto.response.ScholarVerificationResponse;
import ak.dev.irc.app.user.enums.VerificationStatus;
import ak.dev.irc.app.user.service.ScholarVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ScholarVerificationController {

    private final ScholarVerificationService verificationService;

    // ── Applicant ─────────────────────────────────────────────────────────────

    @PostMapping("/api/v1/verification/apply")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ScholarVerificationResponse> apply(
            @Valid @RequestBody ScholarVerificationRequest request) {
        return ResponseEntity.status(201).body(verificationService.apply(request));
    }

    @GetMapping("/api/v1/verification/my-status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ScholarVerificationResponse> myStatus() {
        return ResponseEntity.ok(verificationService.getMyStatus());
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/verification/queue")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<ScholarVerificationResponse>> queue(
            @RequestParam(defaultValue = "PENDING") VerificationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(verificationService.getQueue(status, pageable));
    }

    @PostMapping("/api/v1/admin/verification/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ScholarVerificationResponse> approve(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) VerificationReviewRequest request) {
        return ResponseEntity.ok(verificationService.approve(id, request));
    }

    @PostMapping("/api/v1/admin/verification/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ScholarVerificationResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) VerificationReviewRequest request) {
        return ResponseEntity.ok(verificationService.reject(id, request));
    }
}
