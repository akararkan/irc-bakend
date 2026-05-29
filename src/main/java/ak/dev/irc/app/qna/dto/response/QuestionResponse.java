package ak.dev.irc.app.qna.dto.response;

import ak.dev.irc.app.qna.enums.QuestionStatus;

import java.time.LocalDateTime;
import java.util.List;
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
        /**
         * Server-computed: can a <b>new top-level answer</b> be posted right now?
         * {@code true} only when {@code status == OPEN}, answers aren't locked,
         * and the {@code maxAnswers} cap (if any) isn't yet reached. Use this to
         * show/hide the answer composer — don't recompute it client-side. The
         * cap is enforced server-side too ({@code ANSWER_LIMIT_REACHED} /
         * {@code ANSWERS_LOCKED}); reaching it does <b>not</b> change {@code status}.
         */
        boolean acceptsNewAnswers,
        /** True if the question has ≥1 accepted answer — drive a "resolved" badge on feed cards. */
        boolean hasAcceptedAnswer,
        /** How many answers the author has accepted (0, 1, or more). */
        long acceptedAnswerCount,
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
        LocalDateTime savedAt,
        /** Normalized topic tags (e.g. ["hajj", "ramadan"]). */
        List<String> tags,
        /** Free-text keywords the author added for discoverability. */
        String keywords
) {
}
