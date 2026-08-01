package ak.dev.irc.app.settings.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Accessibility settings block (spec §11) — <b>[C] client-owned</b>, synced but
 * not interpreted.
 *
 * <p>One exception is server-side: {@link #closedCaptions} being on is a hint
 * the client uses to request WebVTT caption renditions, which the media
 * subsystem stores and serves (§20.4). The toggle itself is still cosmetic —
 * the server never reads it to make a decision.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessibilitySettings {

    @Builder.Default private boolean largeText      = false;
    @Builder.Default private boolean highContrast   = false;
    @Builder.Default private boolean screenReader   = false;
    @Builder.Default private boolean closedCaptions = false;
    @Builder.Default private boolean reducedMotion  = false;
    @Builder.Default private boolean voiceNavigation = false;
    @Builder.Default private boolean hapticFeedback  = true;

    public static AccessibilitySettings defaults() {
        return AccessibilitySettings.builder().build();
    }
}
