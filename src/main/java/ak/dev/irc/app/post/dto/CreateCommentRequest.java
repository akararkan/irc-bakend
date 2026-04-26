package ak.dev.irc.app.post.dto;


import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateCommentRequest {

    /** null = top-level comment; non-null = reply */
    private UUID parentId;

    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String textContent;

    // ── media attachment (image / video) ──────────────────────
    private String mediaUrl;
    private String mediaS3Key;
    private String mediaType;          // IMAGE or VIDEO
    private String mediaThumbnailUrl;
    private String mediaThumbnailS3Key;

    // (voice comment fields removed for posts)
}
