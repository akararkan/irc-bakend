package ak.dev.irc.app.security.twofa;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.security.twofa.*} (spec §12).
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.twofa")
public class TwoFaProperties {

    /** Issuer shown in the authenticator app. */
    private String issuer = "IRC";

    /** Number of recovery codes generated per set. */
    private int recoveryCodeCount = 10;

    /** Allowed clock-drift in 30s steps (±1). */
    private int allowedDriftSteps = 1;

    /**
     * Lifetime of the MFA challenge handed back when a password login lands on a
     * 2FA-protected account. Long enough to open an authenticator app and read a
     * code, short enough that a leaked challenge is worthless.
     */
    private long challengeTtlSeconds = 300;

    /**
     * Codes that may be tried against one challenge before it is burned and the
     * password must be re-entered. The ceiling is what keeps a 6-digit code out
     * of brute-force range.
     */
    private int maxChallengeAttempts = 5;
}
