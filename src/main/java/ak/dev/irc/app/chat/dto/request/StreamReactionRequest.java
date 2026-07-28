package ak.dev.irc.app.chat.dto.request;

import lombok.Data;

/** Tap a floating reaction on a live stream. {@code type} is an optional
 *  {@code StreamReactionType} name; absent/unknown falls back to {@code LIKE}. */
@Data
public class StreamReactionRequest {
    private String type;
}
