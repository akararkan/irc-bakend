package ak.dev.irc.app.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * All authentication-related request DTOs.
 */
public final class AuthRequests {

    private AuthRequests() {}

    public record LoginRequest(
            @NotBlank(message = "Username or email is required")
            String username,   // accepts either username or email

            @NotBlank(message = "Password is required")
            String password
    ) {}

    public record RegisterRequest(
            @NotBlank(message = "First name is required")
            @Size(max = 80, message = "First name must be at most 80 characters")
            String fname,

            @NotBlank(message = "Last name is required")
            @Size(max = 80, message = "Last name must be at most 80 characters")
            String lname,

            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
            String username,

            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            String email,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
            String password
    ) {}

    public record RefreshTokenRequest(
            String refreshToken  // optional — can come from cookie instead
    ) {}

    public record LogoutRequest(
            String refreshToken  // optional — can come from cookie instead
    ) {}
}