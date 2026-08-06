package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Inbound reference to an already-uploaded attachment. The bytes are uploaded
 * through the existing media API first; chat only carries the {@code storageKey}.
 */
@Data
public class MediaRefDto {

    /** IMAGE | VIDEO | VOICE | FILE. */
    @NotBlank(message = ChatMessages.VAL_MEDIA_KIND_REQUIRED)
    @Size(max = 16)
    private String kind;

    @NotBlank(message = ChatMessages.VAL_STORAGE_KEY_REQUIRED)
    @Size(max = 512)
    private String storageKey;

    @Size(max = 512)
    private String url;

    @Size(max = 512)
    private String thumbnailKey;

    @Size(max = 512)
    private String thumbnailUrl;

    @Size(max = 128)
    private String mime;

    private Long bytes;
    private Integer width;
    private Integer height;
    private Integer durationMs;

    @Size(max = 20000)
    private String waveform;

    @Size(max = 300)
    private String fileName;

    @Size(max = 500)
    private String altText;
}
