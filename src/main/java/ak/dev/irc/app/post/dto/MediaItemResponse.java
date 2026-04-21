package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.PostMediaType;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class MediaItemResponse {
    private UUID id;
    private PostMediaType mediaType;
    private String url;
    private String thumbnailUrl;
    private String altText;
    private Integer durationSeconds;
    private Long fileSizeBytes;
    private String mimeType;

    private Integer sortOrder;
}