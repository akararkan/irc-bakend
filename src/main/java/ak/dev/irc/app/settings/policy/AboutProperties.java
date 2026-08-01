package ak.dev.irc.app.settings.policy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.about.*} (spec §19). The min-supported-version + force-update
 * flag are the only reliable way to retire a client with a security defect;
 * policy version strings drive the re-consent prompt when a policy changes.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.about")
public class AboutProperties {

    private String minSupportedVersion = "1.0.0";
    private boolean forceUpdate = false;
    private String latestVersion = "1.0.0";

    private String privacyPolicyVersion = "2026-08-01";
    private String termsVersion = "2026-08-01";
    private String guidelinesVersion = "2026-08-01";
}
