package ak.dev.irc.app.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * Ephemeral typing/activity indicator. {@code isTyping=false} is optional —
 * the Redis TTL auto-clears a stale state if the client simply stops sending.
 *
 * <p>Lombok exposes the primitive boolean as property {@code typing}, but the
 * ika frontend has always POSTed {@code isTyping} — the {@code @JsonAlias}
 * accepts both spellings (without it the flag silently bound to {@code false}).</p>
 *
 * <p>{@code activity} says WHAT the sender is doing ({@code RECORDING_VOICE},
 * {@code SENDING_PHOTO}, …). Free-form string on the wire, leniently parsed via
 * {@code ChatAction.parse} (unknown → plain {@code TYPING}); {@code action} is
 * accepted as an alias.</p>
 */
@Data
public class TypingRequest {
    /** Field is named {@code typing} (not {@code isTyping}) on purpose: Jackson
     *  only honours a field-level alias when the field name matches the bean
     *  property derived from the Lombok accessors ({@code isTyping()} /
     *  {@code setTyping} → property {@code typing}). */
    @JsonAlias({"isTyping"})
    private boolean typing;

    @JsonAlias({"action"})
    private String activity;
}
