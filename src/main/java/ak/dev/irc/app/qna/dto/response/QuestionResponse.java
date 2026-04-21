package ak.dev.irc.app.qna.dto.response;

import ak.dev.irc.app.qna.enums.QuestionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuestionResponse(
        UUID id,
        UUID authorId,
        String authorUsername,
        String authorFullName,
        String authorProfileImage,
        String title,
        String body,
        QuestionStatus status,
        Long answerCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}