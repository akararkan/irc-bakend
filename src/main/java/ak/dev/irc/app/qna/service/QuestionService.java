package ak.dev.irc.app.qna.service;

import ak.dev.irc.app.qna.dto.request.CreateAnswerRequest;
import ak.dev.irc.app.qna.dto.request.CreateQuestionRequest;
import ak.dev.irc.app.qna.dto.request.EditAnswerRequest;
import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import ak.dev.irc.app.qna.dto.response.QuestionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface QuestionService {

    QuestionResponse createQuestion(CreateQuestionRequest request, UUID authorId);

    QuestionResponse getQuestion(UUID questionId);

    Page<QuestionResponse> getQuestionFeed(Pageable pageable);

    Page<QuestionResponse> getFeed(Pageable pageable);

    Page<QuestionResponse> getMyQuestions(UUID authorId, Pageable pageable);

    QuestionAnswerResponse addAnswer(UUID questionId, CreateAnswerRequest request, UUID authorId);

    QuestionAnswerResponse editAnswer(UUID questionId, UUID answerId, EditAnswerRequest request, UUID requesterId);

    Page<QuestionAnswerResponse> getAnswers(UUID questionId, Pageable pageable);

    void deleteQuestion(UUID questionId, UUID requesterId);

    void deleteAnswer(UUID questionId, UUID answerId, UUID requesterId);
}