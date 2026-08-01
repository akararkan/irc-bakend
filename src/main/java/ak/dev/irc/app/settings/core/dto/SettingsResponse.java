package ak.dev.irc.app.settings.core.dto;

import ak.dev.irc.app.settings.core.entity.AccessibilitySettings;
import ak.dev.irc.app.settings.core.entity.AppearanceSettings;
import ak.dev.irc.app.settings.core.entity.MediaSettings;
import ak.dev.irc.app.settings.core.entity.MessageSettings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The resolved cosmetic/structured settings object returned by
 * {@code GET /api/v1/settings} (defaults merged). Enforced settings (privacy,
 * security, notifications matrix, presence) are exposed by their own section
 * endpoints and are intentionally not embedded here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsResponse {

    private AppearanceSettings appearance;
    private AccessibilitySettings accessibility;
    private MessageSettings messages;
    private MediaSettings media;
    private int settingsVersion;
}
