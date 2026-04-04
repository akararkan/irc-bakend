package ak.dev.irc.app.user.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserAttachmentResponse(
        UUID          id,
        String        fileUrl,
        String        fileName,
        String        fileType,
        Long          fileSize,
        String        description,
        LocalDateTime uploadedAt
) {}
