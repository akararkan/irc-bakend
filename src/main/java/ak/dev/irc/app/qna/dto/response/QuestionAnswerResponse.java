package ak.dev.irc.app.qna.dto.response;

import ak.dev.irc.app.qna.enums.AnswerReactionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record QuestionAnswerResponse(
        UUID id,
        UUID questionId,
        UUID authorId,
        String authorUsername,
        String authorFullName,
        String authorProfileImage,
        String body,
        // threading
        UUID parentAnswerId,
        long replyCount,
        // media (legacy single media)
        String mediaUrl,
        String mediaType,
        String mediaThumbnailUrl,
        // voice
        String voiceUrl,
        Integer voiceDurationSeconds,
        // links
        String links,
        // attachments (PDF, Word, ZIP, video, audio, images)
        List<AnswerAttachmentResponse> attachments,
        // sources / references
        List<AnswerSourceResponse> sources,
        // status
        boolean accepted,
        /** True if at least one scholar (or the question author) has marked this as best. */
        boolean isBestAnswer,
        /** Number of distinct scholars that have voted this answer as best. */
        long bestAnswerVoteCount,
        /** True if the current viewer (if a scholar) has voted this answer as best. */
        boolean votedByMe,
        boolean edited,
        LocalDateTime editedAt,
        boolean deleted,
        LocalDateTime deletedAt,
        long feedbackCount,
        long reactionCount,
        /** Current viewer's reaction — null if not reacted or anonymous. */
        AnswerReactionType myReaction,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String timeAgo,
        String formattedDate
) {
}
