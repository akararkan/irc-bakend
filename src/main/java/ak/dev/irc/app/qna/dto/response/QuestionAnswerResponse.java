package ak.dev.irc.app.qna.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuestionAnswerResponse(
        UUID id,
        UUID questionId,
        UUID authorId,
        String authorUsername,
        String authorFullName,
        String authorProfileImage,
        String body,
        boolean accepted,
        boolean edited,
        LocalDateTime editedAt,
        boolean deleted,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}