package ak.dev.irc.app.research.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AddCommentRequest(

    @NotBlank(message = "Comment content is required")
    @Size(max = 5000, message = "Comment must not exceed 5 000 characters")
    String content,

    /** Null for top-level comments; set for replies */
    UUID parentId
) {}
