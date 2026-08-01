package ak.dev.irc.app.security.otp.dto;

import jakarta.validation.constraints.NotBlank;

/** OTP request/response DTOs (spec §2). */
public final class OtpDtos {

    private OtpDtos() {}

    public record RequestOtpRequest(@NotBlank String phone, String purpose) {}

    public record VerifyOtpRequest(@NotBlank String phone, @NotBlank String code, String purpose) {}

    /** Deliberately minimal — never echoes whether the number is registered. */
    public record OtpAckResponse(String status) {
        public static OtpAckResponse accepted() { return new OtpAckResponse("ACCEPTED"); }
    }

    public record OtpVerifyResponse(boolean verified, String phone) {}
}
