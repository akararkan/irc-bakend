package ak.dev.irc.app.qna.mapper;

import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.qna.entity.*;
import ak.dev.irc.app.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class QuestionMapper {

    public QuestionResponse toQuestionResponse(Question question) {
        User author = question.getAuthor();
        return new QuestionResponse(
                question.getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                question.getTitle(),
                question.getBody(),
                question.getStatus(),
                question.getAnswerCount(),
                question.isAnswersLocked(),
                question.getMaxAnswers(),
                question.getCreatedAt(),
                question.getUpdatedAt(),
                TimeDisplayUtil.timeAgo(question.getCreatedAt()),
                TimeDisplayUtil.formattedDate(question.getCreatedAt())
        );
    }

    public QuestionAnswerResponse toAnswerResponse(QuestionAnswer answer) {
        User author = answer.getAuthor();
        boolean deleted = answer.isDeleted();

        List<AnswerAttachmentResponse> attachments = deleted ? Collections.emptyList()
                : mapAttachments(answer.getAttachments());

        List<AnswerSourceResponse> sources = deleted ? Collections.emptyList()
                : mapSources(answer.getSources());

        UUID parentAnswerId = answer.getParentAnswer() != null
                ? answer.getParentAnswer().getId()
                : null;
        long replyCount = answer.getReplies() != null
                ? answer.getReplies().stream().filter(r -> !r.isDeleted()).count()
                : 0L;

        return new QuestionAnswerResponse(
                answer.getId(),
                answer.getQuestion().getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                deleted ? null : answer.getBody(),
                parentAnswerId,
                replyCount,
                deleted ? null : answer.getMediaUrl(),
                deleted ? null : answer.getMediaType(),
                deleted ? null : answer.getMediaThumbnailUrl(),
                deleted ? null : answer.getVoiceUrl(),
                deleted ? null : answer.getVoiceDurationSeconds(),
                deleted ? null : answer.getLinks(),
                attachments,
                sources,
                answer.isAccepted(),
                answer.isEdited(),
                answer.getEditedAt(),
                answer.isDeleted(),
                answer.getDeletedAt(),
                answer.getFeedbacks() != null ? answer.getFeedbacks().size() : 0L,
                answer.getReactionCount() != null ? answer.getReactionCount() : 0L,
                answer.getCreatedAt(),
                answer.getUpdatedAt(),
                TimeDisplayUtil.timeAgo(answer.getCreatedAt()),
                TimeDisplayUtil.formattedDate(answer.getCreatedAt())
        );
    }

    public AnswerFeedbackResponse toFeedbackResponse(AnswerFeedback feedback) {
        User author = feedback.getAuthor();
        return new AnswerFeedbackResponse(
                feedback.getId(),
                feedback.getAnswer().getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                feedback.getFeedbackType(),
                feedback.getBody(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt()
        );
    }

    public AnswerAttachmentResponse toAttachmentResponse(AnswerAttachment attachment) {
        return new AnswerAttachmentResponse(
                attachment.getId(),
                attachment.getAnswer().getId(),
                attachment.getFileUrl(),
                attachment.getOriginalFileName(),
                attachment.getMimeType(),
                attachment.getMediaType(),
                attachment.getFileSize(),
                attachment.getDisplayOrder(),
                attachment.getCaption(),
                attachment.getDurationSeconds(),
                attachment.getThumbnailUrl(),
                attachment.getCreatedAt()
        );
    }

    public AnswerSourceResponse toSourceResponse(AnswerSource source) {
        return new AnswerSourceResponse(
                source.getId(),
                source.getAnswer().getId(),
                source.getSourceType(),
                source.getTitle(),
                source.getCitationText(),
                source.getUrl(),
                source.getDoi(),
                source.getIsbn(),
                source.getFileUrl(),
                source.getOriginalFileName(),
                source.getDisplayOrder(),
                source.getCreatedAt()
        );
    }

    private List<AnswerAttachmentResponse> mapAttachments(List<AnswerAttachment> attachments) {
        if (attachments == null) return Collections.emptyList();
        return attachments.stream()
                .map(this::toAttachmentResponse)
                .toList();
    }

    private List<AnswerSourceResponse> mapSources(List<AnswerSource> sources) {
        if (sources == null) return Collections.emptyList();
        return sources.stream()
                .map(this::toSourceResponse)
                .toList();
    }
}
