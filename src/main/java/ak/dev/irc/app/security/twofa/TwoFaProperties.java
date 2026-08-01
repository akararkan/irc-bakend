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
}
