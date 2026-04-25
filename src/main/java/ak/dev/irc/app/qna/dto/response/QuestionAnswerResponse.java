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
        // media
        String mediaUrl,
        String mediaType,
        String mediaThumbnailUrl,
        // voice
        String voiceUrl,
        Integer voiceDurationSeconds,
        // links
        String links,
        // status
        boolean accepted,
        boolean edited,
        LocalDateTime editedAt,
        boolean deleted,
        LocalDateTime deletedAt,
        long feedbackCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}