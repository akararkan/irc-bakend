package ak.dev.irc.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Channel configuration, persisted as a single {@code jsonb} column on the
 * conversation (the CHANNEL sibling of {@link GroupSettings}) so new knobs can
 * be added without a migration.
 *
 * <p>Mutable Lombok bean (not a record) for the same reason as
 * {@link GroupSettings}: Hibernate's JSON mapper and Jackson round-trip it by
 * field, and partial {@code PATCH} updates set individual knobs.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelSettings {

    // ── Basic info ────────────────────────────────────────────────────────────

    /** ISO language code of the channel's content (e.g. "en", "ar", "ckb"). */
    private String language;

    /** ISO country code (e.g. "IQ"). */
    private String country;

    /** Free-form region label (e.g. "Kurdistan"). */
    private String region;

    // ── Appearance ────────────────────────────────────────────────────────────

    /** Hex accent color for the channel header (client-rendered). */
    private String accentColor;

    /** Emoji status shown next to the title (client-rendered). */
    private String emojiStatus;

    /** Storage key / identifier of a chat wallpaper (client-rendered). */
    private String wallpaper;

    // ── Reactions ─────────────────────────────────────────────────────────────

    /** Master toggle — when false, nobody can react to posts in this channel. */
    @Builder.Default
    private boolean reactionsEnabled = true;

    /** Whitelist of allowed reaction emoji. Null/empty = every emoji is allowed. */
    private List<String> allowedReactions;

    // ── Security / content protection ─────────────────────────────────────────

    /** Telegram "protected content" — posts cannot be forwarded out of the
     *  channel, and clients should disable copy/save of media. */
    @Builder.Default
    private boolean protectedContent = false;

    /** Hide the subscriber list from non-admins (the count stays visible). */
    @Builder.Default
    private boolean hiddenSubscribers = false;

    // ── Posting ───────────────────────────────────────────────────────────────

    /** "Sign messages" — posts carry the posting admin's name as an author
     *  signature instead of appearing purely as the channel. */
    @Builder.Default
    private boolean signMessages = false;

    // ── Joining ───────────────────────────────────────────────────────────────

    /** Public channels only: subscribing files a join request an admin must
     *  approve instead of joining immediately. */
    @Builder.Default
    private boolean joinByRequest = false;

    // ── Platform moderation ──────────────────────────────────────────────────

    /** Platform-admin posting freeze — outranks channel-local rights;
     *  set only via the admin dashboard (never by channel owners). */
    @Builder.Default
    private boolean frozenByAdmin = false;

    /** Sensible defaults for a freshly created channel. */
    public static ChannelSettings defaults() {
        return ChannelSettings.builder().build();
    }
}
