package ak.dev.irc.app.qna.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.qna.dto.request.*;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.qna.entity.AnswerFeedback;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.qna.mapper.QuestionMapper;
import ak.dev.irc.app.qna.repository.AnswerFeedbackRepository;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.qna.service.QuestionService;
import ak.dev.irc.app.rabbitmq.publisher.QuestionEventPublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionRepository questionRepository;
    private final QuestionAnswerRepository answerRepository;
    private final AnswerFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final UserFollowRepository followRepository;
    private final UserBlockRepository blockRepository;
    private final QuestionMapper mapper;
    private final QuestionEventPublisher eventPublisher;

    // ══════════════════════════════════════════════════════════════════════════
    //  QUESTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public QuestionResponse createQuestion(CreateQuestionRequest request, UUID authorId) {
        User author = findScholarOrThrow(authorId);

        Question question = Question.builder()
                .author(author)
                .title(request.getTitle().trim())
                .body(request.getBody().trim())
                .status(QuestionStatus.OPEN)
                .answerCount(0L)
                .answersLocked(request.isAnswersLocked())
                .maxAnswers(request.getMaxAnswers())
                .build();

        question = questionRepository.save(question);
        eventPublisher.publishQuestionCreated(question);
        return mapper.toQuestionResponse(question);
    }

    @Override
    @Transactional
    public QuestionResponse editQuestion(UUID questionId, EditQuestionRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);

        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("You can only edit your own question");
        }

        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new BadRequestException("Question title cannot be empty", "EMPTY_TITLE");
            }
            question.setTitle(request.getTitle().trim());
        }
        if (request.getBody() != null) {
            if (request.getBody().isBlank()) {
                throw new BadRequestException("Question body cannot be empty", "EMPTY_BODY");
            }
            question.setBody(request.getBody().trim());
        }
        if (request.getAnswersLocked() != null) {
            question.setAnswersLocked(request.getAnswersLocked());
        }
        if (request.getMaxAnswers() != null) {
            question.setMaxAnswers(request.getMaxAnswers() <= 0 ? null : request.getMaxAnswers());
        }

        question.audit(AuditAction.UPDATE, "Edited question");
        question = questionRepository.save(question);
        return mapper.toQuestionResponse(question);
    }

    @Override
    @Transactional(readOnly = true)
    public QuestionResponse getQuestion(UUID questionId) {
        return mapper.toQuestionResponse(findQuestionOrThrow(questionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionResponse> getQuestionFeed(Pageable pageable) {
        return getFeed(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionResponse> getFeed(Pageable pageable) {
        return questionRepository.findByDeletedAtIsNullOrderByCreatedAtDesc(pageable)
                .map(mapper::toQuestionResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionResponse> getFollowingFeed(UUID userId, Pageable pageable) {
        List<UUID> followingIds = followRepository.findFollowingIds(userId);
        if (followingIds.isEmpty()) {
            return Page.empty(pageable);
        }
        followingIds.removeIf(id -> blockRepository.isBlockedBetween(userId, id));
        if (followingIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return questionRepository.findFollowingFeed(followingIds, pageable)
                .map(mapper::toQuestionResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionResponse> getMyQuestions(UUID authorId, Pageable pageable) {
        findScholarOrThrow(authorId);
        return questionRepository.findByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(authorId, pageable)
                .map(mapper::toQuestionResponse);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ANSWERS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public QuestionAnswerResponse addAnswer(UUID questionId, CreateAnswerRequest request, UUID authorId) {
        User author = findScholarOrThrow(authorId);
        Question question = findQuestionOrThrow(questionId);

        if (question.getStatus() == QuestionStatus.CLOSED || question.getStatus() == QuestionStatus.ARCHIVED) {
            throw new BadRequestException("Question is closed", "QUESTION_CLOSED");
        }

        if (question.isAnswersLocked()) {
            throw new BadRequestException("Answers are locked for this question", "ANSWERS_LOCKED");
        }

        if (question.getMaxAnswers() != null && question.getAnswerCount() >= question.getMaxAnswers()) {
            throw new BadRequestException(
                    "Maximum number of answers (" + question.getMaxAnswers() + ") reached",
                    "ANSWER_LIMIT_REACHED");
        }

        QuestionAnswer answer = QuestionAnswer.builder()
                .question(question)
                .author(author)
                .body(request.getBody().trim())
                .mediaUrl(request.getMediaUrl())
                .mediaType(request.getMediaType())
                .mediaThumbnailUrl(request.getMediaThumbnailUrl())
                .voiceUrl(request.getVoiceUrl())
                .voiceDurationSeconds(request.getVoiceDurationSeconds())
                .links(request.getLinks())
                .build();

        answer = answerRepository.save(answer);

        question.setAnswerCount(question.getAnswerCount() + 1);
        question.setStatus(QuestionStatus.ANSWERED);
        questionRepository.save(question);

        eventPublisher.publishQuestionAnswered(question, answer);

        return mapper.toAnswerResponse(answer);
    }

    @Override
    @Transactional
    public QuestionAnswerResponse editAnswer(UUID questionId, UUID answerId, EditAnswerRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only edit your own answer or answers on your question");
        }

        if (request.getBody() == null || request.getBody().isBlank()) {
            throw new BadRequestException("Answer body cannot be empty", "EMPTY_ANSWER");
        }

        answer.setBody(request.getBody().trim());
        answer.setEdited(true);
        answer.setEditedAt(LocalDateTime.now());
        answer.audit(AuditAction.UPDATE, "Edited answer");
        answer = answerRepository.save(answer);

        return mapper.toAnswerResponse(answer);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionAnswerResponse> getAnswers(UUID questionId, Pageable pageable) {
        findQuestionOrThrow(questionId);
        return answerRepository.findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId, pageable)
                .map(mapper::toAnswerResponse);
    }

    @Override
    @Transactional
    public void deleteQuestion(UUID questionId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("You can only delete your own question");
        }

        question.setDeletedAt(LocalDateTime.now());
        question.setStatus(QuestionStatus.ARCHIVED);
        questionRepository.save(question);
    }

    @Override
    @Transactional
    public void deleteAnswer(UUID questionId, UUID answerId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only delete your own answer or answers on your question");
        }

        answer.setDeletedAt(LocalDateTime.now());
        answer.audit(AuditAction.DELETE, "Deleted answer");
        answerRepository.save(answer);

        long remainingAnswers = Math.max(0L, question.getAnswerCount() - 1);
        question.setAnswerCount(remainingAnswers);
        if (remainingAnswers == 0 && question.getStatus() == QuestionStatus.ANSWERED) {
            question.setStatus(QuestionStatus.OPEN);
        }
        questionRepository.save(question);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ANSWER CONTROLS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public QuestionResponse lockAnswers(UUID questionId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can lock answers");
        }
        question.setAnswersLocked(true);
        question.audit(AuditAction.UPDATE, "Answers locked");
        question = questionRepository.save(question);
        return mapper.toQuestionResponse(question);
    }

    @Override
    @Transactional
    public QuestionResponse unlockAnswers(UUID questionId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can unlock answers");
        }
        question.setAnswersLocked(false);
        question.audit(AuditAction.UPDATE, "Answers unlocked");
        question = questionRepository.save(question);
        return mapper.toQuestionResponse(question);
    }

    @Override
    @Transactional
    public QuestionResponse setAnswerLimit(UUID questionId, Integer maxAnswers, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can set the answer limit");
        }
        question.setMaxAnswers(maxAnswers != null && maxAnswers <= 0 ? null : maxAnswers);
        question.audit(AuditAction.UPDATE, "Answer limit set to " + (maxAnswers == null ? "unlimited" : maxAnswers));
        question = questionRepository.save(question);
        return mapper.toQuestionResponse(question);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ACCEPT / UNACCEPT
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public QuestionAnswerResponse acceptAnswer(UUID questionId, UUID answerId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can accept answers");
        }

        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        answer.setAccepted(true);
        answer.audit(AuditAction.UPDATE, "Answer accepted");
        answer = answerRepository.save(answer);
        return mapper.toAnswerResponse(answer);
    }

    @Override
    @Transactional
    public QuestionAnswerResponse unacceptAnswer(UUID questionId, UUID answerId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can unaccept answers");
        }

        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        answer.setAccepted(false);
        answer.audit(AuditAction.UPDATE, "Answer unaccepted");
        answer = answerRepository.save(answer);
        return mapper.toAnswerResponse(answer);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FEEDBACK
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public AnswerFeedbackResponse addFeedback(UUID questionId, UUID answerId,
                                               AddFeedbackRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        findScholarOrThrow(requesterId);

        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        // Only the question author or admins can give feedback
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can give feedback on answers");
        }

        if (feedbackRepository.existsByAnswerIdAndAuthorId(answerId, requesterId)) {
            throw new DuplicateResourceException("Feedback", "answer+author", "already exists");
        }

        User feedbackAuthor = userRepository.getReferenceById(requesterId);

        AnswerFeedback feedback = AnswerFeedback.builder()
                .answer(answer)
                .author(feedbackAuthor)
                .feedbackType(request.getFeedbackType())
                .body(request.getBody() != null ? request.getBody().trim() : null)
                .build();
        feedback.audit(AuditAction.CREATE, "Feedback added: " + request.getFeedbackType());

        feedback = feedbackRepository.save(feedback);
        return mapper.toFeedbackResponse(feedback);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnswerFeedbackResponse> getFeedback(UUID questionId, UUID answerId) {
        findQuestionOrThrow(questionId);
        return feedbackRepository.findByAnswerIdOrderByCreatedAtAsc(answerId).stream()
                .map(mapper::toFeedbackResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteFeedback(UUID questionId, UUID answerId, UUID feedbackId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can delete feedback");
        }

        AnswerFeedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", "id", feedbackId));

        if (!feedback.getAnswer().getId().equals(answerId)) {
            throw new BadRequestException("Feedback does not belong to this answer", "FEEDBACK_MISMATCH");
        }

        feedbackRepository.delete(feedback);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Question findQuestionOrThrow(UUID questionId) {
        return questionRepository.findByIdAndDeletedAtIsNull(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", "id", questionId));
    }

    private User findScholarOrThrow(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.getRole() != Role.SCHOLAR
                && user.getRole() != Role.ADMIN
                && user.getRole() != Role.SUPER_ADMIN) {
            throw new ForbiddenException("Only scholars can use the Q&A area");
        }

        return user;
    }

    private boolean canManageQuestion(Question question, UUID requesterId) {
        if (requesterId == null) return false;

        User requester = userRepository.findById(requesterId).orElse(null);
        if (requester == null) return false;

        return question.getAuthor().getId().equals(requesterId)
                || requester.getRole() == Role.ADMIN
                || requester.getRole() == Role.SUPER_ADMIN;
    }

    private boolean canManageAnswer(Question question, QuestionAnswer answer, UUID requesterId) {
        if (requesterId == null) return false;

        User requester = userRepository.findById(requesterId).orElse(null);
        if (requester == null) return false;

        return answer.getAuthor().getId().equals(requesterId)
                || question.getAuthor().getId().equals(requesterId)
                || requester.getRole() == Role.ADMIN
                || requester.getRole() == Role.SUPER_ADMIN;
    }
}