package ak.dev.irc.app.security.stepup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.security.step-up.*} (spec §22.3).
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.step-up")
public class StepUpProperties {

    /** How long a fresh step-up authentication remains valid, seconds. */
    private long ttlSeconds = 300;
}
