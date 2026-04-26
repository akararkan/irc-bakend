package ak.dev.irc.app.qna.dto.response;

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
        boolean edited,
        LocalDateTime editedAt,
        boolean deleted,
        LocalDateTime deletedAt,
        long feedbackCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
