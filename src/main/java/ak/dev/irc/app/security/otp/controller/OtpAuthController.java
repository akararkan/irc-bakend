package ak.dev.irc.app.security.otp.controller;

import ak.dev.irc.app.security.otp.dto.OtpDtos.*;
import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import ak.dev.irc.app.security.otp.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * OTP request/verify surface (spec §2.2). Public — this is a pre-auth flow.
 *
 * <p><b>Scope note:</b> this delivers the full OTP machinery (hashed code, Redis
 * TTL, attempts, rate-limit, enumeration-safe response). Issuing a JWT session
 * for a phone-<em>primary</em> account is the remaining integration seam in
 * {@code AuthServiceImpl} — phone columns exist on {@code User} but no accounts
 * are phone-primary yet, so verify returns {@code verified:true} without minting
 * tokens. Logged-in phone binding lives in {@code SecurityController}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/otp")
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class OtpAuthController {

    private final OtpService otpService;

    /** Always 202 — never reveals whether the number is registered. */
    @PostMapping("/request")
    public ResponseEntity<OtpAckResponse> request(@Valid @RequestBody RequestOtpRequest req,
                                                  HttpServletRequest http) {
        try {
            otpService.requestOtp(req.phone(),
                    OtpPurpose.parse(req.purpose(), OtpPurpose.LOGIN),
                    clientIp(http), http.getHeader("X-Device-Id"));
        } catch (Exception ex) {
            // Swallow everything except the response shape — enumeration safety.
            log.debug("[OTP] request suppressed error: {}", ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OtpAckResponse.accepted());
    }

    @PostMapping("/verify")
    public ResponseEntity<OtpVerifyResponse> verify(@Valid @RequestBody VerifyOtpRequest req) {
        String phone = otpService.verifyOtp(req.phone(), req.code(),
                OtpPurpose.parse(req.purpose(), OtpPurpose.LOGIN));
        return ResponseEntity.ok(new OtpVerifyResponse(true, phone));
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
