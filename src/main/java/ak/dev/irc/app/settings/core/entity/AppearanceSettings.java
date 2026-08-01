package ak.dev.irc.app.settings.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Appearance settings block (spec §10) — <b>[C] client-owned</b>.
 *
 * <p>Stored verbatim in {@code user_settings.appearance} JSONB and synced for
 * cross-device continuity; the backend <em>never interprets</em> these values.
 * Adding a theme therefore requires no backend release. Unknown keys are
 * ignored on read so a newer client can add fields without a schema change.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppearanceSettings {

    /** LIGHT | DARK | SYSTEM. */
    @Builder.Default
    private String theme = "SYSTEM";

    /** SMALL | MEDIUM | LARGE | XLARGE. */
    @Builder.Default
    private String fontSize = "MEDIUM";

    /** UI density: COMFORTABLE | COMPACT. */
    @Builder.Default
    private String density = "COMFORTABLE";

    /** Interface scaling factor, 0.8–1.4 (client-applied). */
    @Builder.Default
    private Double interfaceScale = 1.0;

    /** Disable non-essential motion/animation. */
    @Builder.Default
    private boolean reducedMotion = false;

    /** Hex accent colour, e.g. {@code #1B7F5A}. */
    @Builder.Default
    private String accentColor = "#1B7F5A";

    public static AppearanceSettings defaults() {
        return AppearanceSettings.builder().build();
    }
}
