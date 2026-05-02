package ak.dev.irc.app.qna.service;

import ak.dev.irc.app.qna.dto.request.*;
import ak.dev.irc.app.qna.dto.response.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface QuestionService {

    QuestionResponse createQuestion(CreateQuestionRequest request, UUID authorId);

    QuestionResponse editQuestion(UUID questionId, EditQuestionRequest request, UUID requesterId);

    QuestionResponse getQuestion(UUID questionId);

    Page<QuestionResponse> getQuestionFeed(Pageable pageable);

    Page<QuestionResponse> getFeed(Pageable pageable);

    Page<QuestionResponse> getFollowingFeed(UUID userId, Pageable pageable);

    Page<QuestionResponse> getMyQuestions(UUID authorId, Pageable pageable);

    // ── Answers ──────────────────────────────────────────────────────────────

    QuestionAnswerResponse addAnswer(UUID questionId, CreateAnswerRequest request, UUID authorId);

    QuestionAnswerResponse editAnswer(UUID questionId, UUID answerId, EditAnswerRequest request, UUID requesterId);

    Page<QuestionAnswerResponse> getAnswers(UUID questionId, Pageable pageable);

    Page<QuestionAnswerResponse> getReanswers(UUID questionId, UUID answerId, Pageable pageable);

    void deleteQuestion(UUID questionId, UUID requesterId);

    void deleteAnswer(UUID questionId, UUID answerId, UUID requesterId);

    // ── Answer controls ──────────────────────────────────────────────────────

    QuestionResponse lockAnswers(UUID questionId, UUID requesterId);

    QuestionResponse unlockAnswers(UUID questionId, UUID requesterId);

    QuestionResponse setAnswerLimit(UUID questionId, Integer maxAnswers, UUID requesterId);

    // ── Accept / unaccept (multiple best answers) ───────────────────────────

    QuestionAnswerResponse acceptAnswer(UUID questionId, UUID answerId, UUID requesterId);

    QuestionAnswerResponse unacceptAnswer(UUID questionId, UUID answerId, UUID requesterId);

    // ── Feedback ─────────────────────────────────────────────────────────────

    AnswerFeedbackResponse addFeedback(UUID questionId, UUID answerId, AddFeedbackRequest request, UUID requesterId);

    AnswerFeedbackResponse editFeedback(UUID questionId, UUID answerId, UUID feedbackId, AddFeedbackRequest request, UUID requesterId);

    List<AnswerFeedbackResponse> getFeedback(UUID questionId, UUID answerId);

    void deleteFeedback(UUID questionId, UUID answerId, UUID feedbackId, UUID requesterId);

    // ── Attachments (upload files to answer) ─────────────────────────────────

    AnswerAttachmentResponse uploadAttachment(UUID questionId, UUID answerId, MultipartFile file,
                                               String caption, Integer displayOrder, UUID requesterId);

    List<AnswerAttachmentResponse> getAttachments(UUID questionId, UUID answerId);

    void deleteAttachment(UUID questionId, UUID answerId, UUID attachmentId, UUID requesterId);

    // ── Sources / references ─────────────────────────────────────────────────

    AnswerSourceResponse addSource(UUID questionId, UUID answerId, CreateAnswerSourceRequest request, UUID requesterId);

    AnswerSourceResponse updateSource(UUID questionId, UUID answerId, UUID sourceId,
                                      UpdateAnswerSourceRequest request, UUID requesterId);

    List<AnswerSourceResponse> getSources(UUID questionId, UUID answerId);

    void deleteSource(UUID questionId, UUID answerId, UUID sourceId, UUID requesterId);

    // ── Attachments update ──────────────────────────────────────────────────

    AnswerAttachmentResponse updateAttachment(UUID questionId, UUID answerId, UUID attachmentId,
                                              UpdateAnswerAttachmentRequest request, UUID requesterId);

    // ── Reactions (apply to top-level answers AND reanswers) ────────────────

    QuestionAnswerResponse reactToAnswer(UUID questionId, UUID answerId,
                                         ReactToAnswerRequest request, UUID requesterId);

    void removeAnswerReaction(UUID questionId, UUID answerId, UUID requesterId);
}
