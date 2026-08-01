package ak.dev.irc.app.settings.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Messaging cosmetic settings block (spec §9) — <b>[C] client-owned</b>.
 *
 * <p>Chat wallpaper, theme and font size are stored here purely for cross-device
 * sync. The enforced messaging settings (auto-delete TTL, edit window, archive,
 * read receipts) live server-side on the conversation / member rows and in
 * {@code ChatUserSettings}, never here.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageSettings {

    /** Wallpaper reference — a media id, a preset key, or a hex colour. */
    @Builder.Default
    private String wallpaper = "DEFAULT";

    /** Bubble/chat theme preset key. */
    @Builder.Default
    private String chatTheme = "DEFAULT";

    /** SMALL | MEDIUM | LARGE. */
    @Builder.Default
    private String fontSize = "MEDIUM";

    /** Enter key sends the message (vs. inserting a newline). */
    @Builder.Default
    private boolean enterToSend = true;

    public static MessageSettings defaults() {
        return MessageSettings.builder().build();
    }
}
