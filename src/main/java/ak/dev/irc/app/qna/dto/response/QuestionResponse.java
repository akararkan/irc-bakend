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
        Long viewCount,
        /** Number of users who bookmarked this question. */
        Long saveCount,
        boolean answersLocked,
        Integer maxAnswers,
        /** Whether the current viewer (if authed) has bookmarked this question. */
        boolean isSaved,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String timeAgo,
        String formattedDate,
        /**
         * When the viewer bookmarked this question — populated only by saved-list
         * endpoints ({@code GET /me/saved}, {@code /me/saved/collection}). Null on
         * every other endpoint. Distinct from {@code createdAt} (when the
         * question was posted).
         */
        LocalDateTime savedAt
) {
}
