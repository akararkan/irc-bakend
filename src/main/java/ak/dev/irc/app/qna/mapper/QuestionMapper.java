package ak.dev.irc.app.qna.mapper;

import ak.dev.irc.app.qna.dto.response.AnswerFeedbackResponse;
import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import ak.dev.irc.app.qna.dto.response.QuestionResponse;
import ak.dev.irc.app.qna.entity.AnswerFeedback;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.user.entity.User;
import org.springframework.stereotype.Component;

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
                question.getUpdatedAt()
        );
    }

    public QuestionAnswerResponse toAnswerResponse(QuestionAnswer answer) {
        User author = answer.getAuthor();
        boolean deleted = answer.isDeleted();
        return new QuestionAnswerResponse(
                answer.getId(),
                answer.getQuestion().getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                deleted ? null : answer.getBody(),
                deleted ? null : answer.getMediaUrl(),
                deleted ? null : answer.getMediaType(),
                deleted ? null : answer.getMediaThumbnailUrl(),
                deleted ? null : answer.getVoiceUrl(),
                deleted ? null : answer.getVoiceDurationSeconds(),
                deleted ? null : answer.getLinks(),
                answer.isAccepted(),
                answer.isEdited(),
                answer.getEditedAt(),
                answer.isDeleted(),
                answer.getDeletedAt(),
                answer.getFeedbacks() != null ? answer.getFeedbacks().size() : 0L,
                answer.getCreatedAt(),
                answer.getUpdatedAt()
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
}