package ak.dev.irc.app.qna.dto.response;

import ak.dev.irc.app.research.enums.SourceType;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerSourceResponse(
        UUID id,
        UUID answerId,
        SourceType sourceType,
        String title,
        String citationText,
        String url,
        String doi,
        String isbn,
        String fileUrl,
        String originalFileName,
        Integer displayOrder,
        LocalDateTime createdAt
) {}
