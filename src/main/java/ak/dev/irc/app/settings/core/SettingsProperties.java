package ak.dev.irc.app.settings.core;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code app.settings.*} configuration block.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.settings")
public class SettingsProperties {

    /** TTL for the resolved {@code settings:{userId}} Redis cache. */
    private long cacheTtlSeconds = 600;
}
