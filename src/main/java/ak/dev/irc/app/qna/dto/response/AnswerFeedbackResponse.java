package ak.dev.irc.app.qna.dto.response;

import ak.dev.irc.app.qna.enums.FeedbackType;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnswerFeedbackResponse(
        UUID id,
        UUID answerId,
        UUID authorId,
        String authorUsername,
        String authorFullName,
        String authorProfileImage,
        FeedbackType feedbackType,
        String body,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}