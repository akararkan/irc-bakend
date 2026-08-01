package ak.dev.irc.app.security.dto;

import ak.dev.irc.app.security.login.entity.LoginEvent;
import ak.dev.irc.app.user.entity.RefreshToken;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response DTOs for the security surface (spec §12). */
public final class SecurityDtos {

    private SecurityDtos() {}

    public record SessionResponse(UUID sid, String deviceName, String platform, String ip,
                                  LocalDateTime lastSeenAt, LocalDateTime createdAt, boolean trusted) {
        public static SessionResponse of(RefreshToken t) {
            return new SessionResponse(
                    t.getSid(), t.getDeviceName(), t.getPlatform(), t.getIpAddress(),
                    t.getLastSeenAt(), t.getCreatedAt(), t.isTrustedNow());
        }
    }

    public record TwoFaSetupResponse(String provisioningUri, String secret) {}

    public record CodeRequest(@NotBlank String code) {}

    public record RecoveryCodesResponse(List<String> codes) {}

    public record LoginEventResponse(String ip, String userAgent, String method,
                                     String outcome, LocalDateTime ts) {
        public static LoginEventResponse of(LoginEvent e) {
            return new LoginEventResponse(e.getIp(), e.getUserAgent(), e.getMethod(),
                    e.getOutcome(), e.getTs());
        }
    }

    public record StepUpRequest(String password, String code) {}

    public record TrustDeviceRequest(int days) {}

    public record PhoneRequest(@NotBlank String phone) {}

    public record PhoneVerifyRequest(@NotBlank String phone, @NotBlank String code) {}

    public record TwoFaStatusResponse(boolean enabled, long recoveryCodesRemaining) {}
}
