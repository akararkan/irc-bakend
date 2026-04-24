package ak.dev.irc.app.qna.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.qna.dto.request.CreateAnswerRequest;
import ak.dev.irc.app.qna.dto.request.CreateQuestionRequest;
import ak.dev.irc.app.qna.dto.request.EditAnswerRequest;
import ak.dev.irc.app.qna.dto.request.EditQuestionRequest;
import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import ak.dev.irc.app.qna.dto.response.QuestionResponse;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.qna.mapper.QuestionMapper;
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
    private final UserRepository userRepository;
    private final UserFollowRepository followRepository;
    private final UserBlockRepository blockRepository;
    private final QuestionMapper mapper;
    private final QuestionEventPublisher eventPublisher;

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

    @Override
    @Transactional
    public QuestionAnswerResponse addAnswer(UUID questionId, CreateAnswerRequest request, UUID authorId) {
        User author = findScholarOrThrow(authorId);
        Question question = findQuestionOrThrow(questionId);

        if (question.getStatus() == QuestionStatus.CLOSED || question.getStatus() == QuestionStatus.ARCHIVED) {
            throw new BadRequestException("Question is closed", "QUESTION_CLOSED");
        }

        QuestionAnswer answer = QuestionAnswer.builder()
                .question(question)
                .author(author)
                .body(request.getBody().trim())
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
        if (requesterId == null) {
            return false;
        }

        User requester = userRepository.findById(requesterId).orElse(null);
        if (requester == null) {
            return false;
        }

        return question.getAuthor().getId().equals(requesterId)
                || requester.getRole() == Role.ADMIN
                || requester.getRole() == Role.SUPER_ADMIN;
    }

    private boolean canManageAnswer(Question question, QuestionAnswer answer, UUID requesterId) {
        if (requesterId == null) {
            return false;
        }

        User requester = userRepository.findById(requesterId).orElse(null);
        if (requester == null) {
            return false;
        }

        return answer.getAuthor().getId().equals(requesterId)
                || question.getAuthor().getId().equals(requesterId)
                || requester.getRole() == Role.ADMIN
                || requester.getRole() == Role.SUPER_ADMIN;
    }
}