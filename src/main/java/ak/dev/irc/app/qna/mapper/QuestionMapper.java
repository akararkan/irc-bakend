package ak.dev.irc.app.qna.mapper;

import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import ak.dev.irc.app.qna.dto.response.QuestionResponse;
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
                question.getCreatedAt(),
                question.getUpdatedAt()
        );
    }

    public QuestionAnswerResponse toAnswerResponse(QuestionAnswer answer) {
        User author = answer.getAuthor();
        return new QuestionAnswerResponse(
                answer.getId(),
                answer.getQuestion().getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                answer.getBody(),
                answer.isAccepted(),
                answer.isEdited(),
                answer.getEditedAt(),
                answer.isDeleted(),
                answer.getDeletedAt(),
                answer.getCreatedAt(),
                answer.getUpdatedAt()
        );
    }
}