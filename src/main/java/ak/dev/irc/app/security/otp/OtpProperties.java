package ak.dev.irc.app.security.otp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.otp.*} (spec §2.4).
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.otp")
public class OtpProperties {

    /** Number of digits in the code. */
    private int codeLength = 6;

    /** Time-to-live for a challenge, seconds. */
    private long ttlSeconds = 300;

    /** Maximum verification attempts before the challenge is burned. */
    private int maxAttempts = 5;

    /**
     * Server pepper for HMAC of the code — the plaintext code is never stored,
     * only {@code HMAC-SHA256(code, pepper)}. If the DB leaks, live OTPs do not.
     * Must be set in production.
     */
    private String pepper = "";

    /** Resends allowed per number per hour. */
    private int resendPerNumberPerHour = 3;

    /** Resends allowed per IP per hour. */
    private int resendPerIpPerHour = 10;
}
