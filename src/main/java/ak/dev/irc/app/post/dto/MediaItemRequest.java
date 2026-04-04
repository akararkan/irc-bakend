package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.PostMediaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MediaItemRequest {

    @NotNull
    private PostMediaType mediaType;

    @NotBlank
    private String url;

    private String thumbnailUrl;
    private String altText;
    private Integer durationSeconds;
    private Long fileSizeBytes;
    private String mimeType;

    /** For VOICE_NOTE media items */
    private String waveformData;
    private String transcript;

    private Integer sortOrder = 0;
}