package ak.dev.irc.app.security.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.dto.SecurityDtos.*;
import ak.dev.irc.app.security.login.service.LoginEventService;
import ak.dev.irc.app.security.phone.PhoneService;
import ak.dev.irc.app.security.session.SessionService;
import ak.dev.irc.app.security.stepup.StepUpService;
import ak.dev.irc.app.security.twofa.service.RecoveryCodeService;
import ak.dev.irc.app.security.twofa.service.TwoFactorService;
import ak.dev.irc.app.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Security settings surface (spec §12): active sessions, 2FA, recovery codes,
 * login history, step-up authentication, and phone verification. Sensitive
 * operations (disable 2FA, regenerate recovery codes) require a fresh
 * {@link StepUpService step-up}.
 */
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SecurityController {

    private final SessionService sessionService;
    private final TwoFactorService twoFactorService;
    private final RecoveryCodeService recoveryCodeService;
    private final LoginEventService loginEventService;
    private final StepUpService stepUpService;
    private final PhoneService phoneService;

    // ── Sessions (spec §12) ──────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionResponse>> sessions() {
        return ResponseEntity.ok(sessionService.activeSessions(SecurityUtils.requireCurrentUserId()));
    }

    @DeleteMapping("/sessions/{sid}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID sid) {
        sessionService.revoke(SecurityUtils.requireCurrentUserId(), sid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{sid}/trust")
    public ResponseEntity<Void> trustDevice(@PathVariable UUID sid,
                                            @RequestBody TrustDeviceRequest req) {
        sessionService.trustDevice(SecurityUtils.requireCurrentUserId(), sid, req.days());
        return ResponseEntity.noContent().build();
    }

    // ── Two-factor (spec §12) ────────────────────────────────────────────────

    @PostMapping("/2fa/setup")
    public ResponseEntity<TwoFaSetupResponse> setup2fa() {
        var r = twoFactorService.setup(SecurityUtils.requireCurrentUserId());
        return ResponseEntity.ok(new TwoFaSetupResponse(r.provisioningUri(), r.secret()));
    }

    /** Confirm enrolment. On the enabling call, returns the one-time recovery codes. */
    @PostMapping("/2fa/verify")
    public ResponseEntity<RecoveryCodesResponse> verify2fa(@Valid @RequestBody CodeRequest req) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        boolean newlyEnabled = twoFactorService.confirmEnable(userId, req.code());
        List<String> codes = newlyEnabled ? recoveryCodeService.regenerate(userId) : List.of();
        return ResponseEntity.ok(new RecoveryCodesResponse(codes));
    }

    @PostMapping("/2fa/disable")
    public ResponseEntity<Void> disable2fa() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        stepUpService.requireRecentStepUp(userId);      // sensitive — fresh auth required
        twoFactorService.disable(userId);
        recoveryCodeService.clear(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/2fa/status")
    public ResponseEntity<TwoFaStatusResponse> status2fa() {
        User me = SecurityUtils.getCurrentUser().orElseThrow();
        return ResponseEntity.ok(new TwoFaStatusResponse(
                me.isTwoFactorEnabled(), recoveryCodeService.remaining(me.getId())));
    }

    @PostMapping("/recovery-codes/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        stepUpService.requireRecentStepUp(userId);      // sensitive — fresh auth required
        return ResponseEntity.ok(new RecoveryCodesResponse(recoveryCodeService.regenerate(userId)));
    }

    // ── Login history (spec §12) ─────────────────────────────────────────────

    @GetMapping("/login-history")
    public ResponseEntity<Page<LoginEventResponse>> loginHistory(Pageable pageable) {
        return ResponseEntity.ok(loginEventService
                .history(SecurityUtils.requireCurrentUserId(), pageable)
                .map(LoginEventResponse::of));
    }

    // ── Step-up authentication (spec §22.3) ──────────────────────────────────

    /** Re-authenticate with the password to arm the step-up window for sensitive ops. */
    @PostMapping("/step-up")
    public ResponseEntity<Void> stepUp(@RequestBody StepUpRequest req) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        if (req.password() != null && !req.password().isBlank()) {
            stepUpService.stepUpWithPassword(userId, req.password());
        } else if (req.code() != null && !req.code().isBlank()) {
            if (!twoFactorService.verifyCode(userId, req.code())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            stepUpService.arm(userId);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        return ResponseEntity.noContent().build();
    }

    // ── Phone verification (spec §4) ─────────────────────────────────────────

    @PostMapping("/phone/request")
    public ResponseEntity<Void> requestPhone(@Valid @RequestBody PhoneRequest req,
                                             HttpServletRequest http) {
        phoneService.requestVerification(SecurityUtils.requireCurrentUserId(), req.phone(), clientIp(http));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/phone/verify")
    public ResponseEntity<PhoneVerifyAck> verifyPhone(@Valid @RequestBody PhoneVerifyRequest req) {
        String e164 = phoneService.confirm(SecurityUtils.requireCurrentUserId(), req.phone(), req.code());
        return ResponseEntity.ok(new PhoneVerifyAck(true, e164));
    }

    public record PhoneVerifyAck(boolean verified, String phone) {}

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
