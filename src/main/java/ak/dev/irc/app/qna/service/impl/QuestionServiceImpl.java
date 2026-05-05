package ak.dev.irc.app.qna.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.CursorPage;
import ak.dev.irc.app.qna.dto.request.*;
import ak.dev.irc.app.rabbitmq.event.user.MentionSource;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.qna.entity.*;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import ak.dev.irc.app.qna.mapper.QuestionMapper;
import ak.dev.irc.app.qna.realtime.QnaRealtimeBroadcaster;
import ak.dev.irc.app.qna.realtime.QnaRealtimeEvent;
import ak.dev.irc.app.qna.realtime.QnaRealtimeEventType;
import ak.dev.irc.app.qna.repository.*;
import ak.dev.irc.app.qna.service.QuestionService;
import ak.dev.irc.app.rabbitmq.publisher.QuestionEventPublisher;
import ak.dev.irc.app.research.enums.MediaType;
import ak.dev.irc.app.research.enums.SourceType;
import ak.dev.irc.app.research.service.S3StorageService;
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
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionRepository questionRepository;
    private final QuestionAnswerRepository answerRepository;
    private final AnswerFeedbackRepository feedbackRepository;
    private final AnswerAttachmentRepository attachmentRepository;
    private final AnswerSourceRepository sourceRepository;
    private final AnswerReactionRepository reactionRepository;
    private final UserRepository userRepository;
    private final UserFollowRepository followRepository;
    private final UserBlockRepository blockRepository;
    private final QuestionMapper mapper;
    private final QuestionEventPublisher eventPublisher;
    private final S3StorageService storageService;
    private final MentionService mentionService;
    private final SocialGuard socialGuard;
    private final FollowingIdsCache followingIdsCache;
    private final QnaRealtimeBroadcaster realtime;

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
                .status(ak.dev.irc.app.qna.enums.QuestionStatus.OPEN)
                .answerCount(0L)
                .answersLocked(request.isAnswersLocked())
                .maxAnswers(request.getMaxAnswers())
                .build();

        question = questionRepository.save(question);
        eventPublisher.publishQuestionCreated(question);

        // @mentions in the question title + body — @followers fan-out allowed
        // because creating a question is a top-level publication.
        StringBuilder mentionText = new StringBuilder();
        if (question.getTitle() != null) mentionText.append(question.getTitle());
        if (question.getBody() != null) {
            if (mentionText.length() > 0) mentionText.append(' ');
            mentionText.append(question.getBody());
        }
        mentionService.scanAndPublish(
                mentionText.toString(),
                MentionSource.QUESTION,
                question.getId(),
                null,
                authorId,
                author.getUsername(),
                /* allowFollowersToken */ true);

        return mapper.toQuestionResponse(question);
    }

    @Override
    @Transactional
    public QuestionResponse editQuestion(UUID questionId, EditQuestionRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);

        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("You can only edit your own question");
        }

        // Capture the prior text (title + body) so the mention delta scan
        // ignores handles that were already in the question.
        String previousMentionText = joinForMention(question.getTitle(), question.getBody());

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

        User author = question.getAuthor();
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(QnaRealtimeEventType.QUESTION_UPDATED)
                .questionId(question.getId())
                .actorId(requesterId)
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .body(question.getBody())
                .build());

        // Mention delta — only newly added @-handles get a notification.
        mentionService.scanAndPublishDelta(
                previousMentionText,
                joinForMention(question.getTitle(), question.getBody()),
                MentionSource.QUESTION,
                question.getId(),
                null,
                requesterId,
                author.getUsername());

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
    public CursorPage<QuestionResponse> getFeedCursor(LocalDateTime cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        var pageReq = org.springframework.data.domain.PageRequest.of(0, safeLimit + 1);
        List<Question> rows = (cursor == null)
                ? questionRepository.findFeedFirstPage(pageReq)
                : questionRepository.findFeedAfter(cursor, pageReq);

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);

        List<QuestionResponse> items = rows.stream().map(mapper::toQuestionResponse).toList();
        LocalDateTime nextCursor = hasMore && !items.isEmpty()
                ? rows.get(rows.size() - 1).getCreatedAt()
                : null;

        return CursorPage.<QuestionResponse>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionResponse> getFollowingFeed(UUID userId, Pageable pageable) {
        // Cached, block-filtered following set — no per-row block scan and a
        // 1-min Redis hit on the hot scroll path.
        List<UUID> followingIds = followingIdsCache.getFilteredFollowingIds(userId);
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

        if (question.getStatus() == ak.dev.irc.app.qna.enums.QuestionStatus.CLOSED
                || question.getStatus() == ak.dev.irc.app.qna.enums.QuestionStatus.ARCHIVED) {
            throw new BadRequestException("Question is closed", "QUESTION_CLOSED");
        }

        if (question.isAnswersLocked()) {
            throw new BadRequestException("Answers are locked for this question", "ANSWERS_LOCKED");
        }

        // Block guard — refuse answers / reanswers across any block edge with
        // the question author (and, for reanswers, the parent answer author).
        socialGuard.requireNotBlockedBetween(
                authorId, question.getAuthor().getId(), "ANSWER_BLOCKED_RELATIONSHIP");

        // Resolve parent if this is a reanswer (reply to another answer) —
        // mirrors the post-comment top-level-vs-reply branching.
        QuestionAnswer parent = null;
        if (request.getParentAnswerId() != null) {
            parent = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(
                            request.getParentAnswerId(), questionId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Parent answer", "id", request.getParentAnswerId()));
            socialGuard.requireNotBlockedBetween(
                    authorId, parent.getAuthor().getId(), "REANSWER_BLOCKED_RELATIONSHIP");
            // Bump the parent's denormalised replyCount up front so it's
            // visible immediately on the parent's row in the listing query.
            answerRepository.updateReplyCount(parent.getId(), 1);
            // Reanswers do not count toward maxAnswers — only top-level answers do.
        } else {
            if (question.getMaxAnswers() != null && question.getAnswerCount() >= question.getMaxAnswers()) {
                throw new BadRequestException(
                        "Maximum number of answers (" + question.getMaxAnswers() + ") reached",
                        "ANSWER_LIMIT_REACHED");
            }
        }

        QuestionAnswer answer = QuestionAnswer.builder()
                .question(question)
                .author(author)
                .parentAnswer(parent)
                .body(request.getBody().trim())
                .mediaUrl(request.getMediaUrl())
                .mediaType(request.getMediaType())
                .mediaThumbnailUrl(request.getMediaThumbnailUrl())
                .voiceUrl(request.getVoiceUrl())
                .voiceDurationSeconds(request.getVoiceDurationSeconds())
                .links(request.getLinks())
                .build();

        answer = answerRepository.save(answer);

        // Save sources if provided
        if (request.getSources() != null && !request.getSources().isEmpty()) {
            int order = 0;
            for (CreateAnswerSourceRequest srcReq : request.getSources()) {
                AnswerSource source = AnswerSource.builder()
                        .answer(answer)
                        .sourceType(srcReq.getSourceType())
                        .title(srcReq.getTitle().trim())
                        .citationText(srcReq.getCitationText() != null ? srcReq.getCitationText().trim() : null)
                        .url(srcReq.getUrl())
                        .doi(srcReq.getDoi())
                        .isbn(srcReq.getIsbn())
                        .displayOrder(order++)
                        .build();
                sourceRepository.save(source);
                answer.getSources().add(source);
            }
        }

        // Only top-level answers move the question into ANSWERED and bump the count.
        if (parent == null) {
            question.setAnswerCount(question.getAnswerCount() + 1);
            question.setStatus(ak.dev.irc.app.qna.enums.QuestionStatus.ANSWERED);
            questionRepository.save(question);
        }

        eventPublisher.publishQuestionAnswered(question, answer);

        // @mentions in the answer body — no @followers fan-out from answers.
        mentionService.scanAndPublish(
                answer.getBody(),
                MentionSource.QUESTION_ANSWER,
                answer.getId(),
                question.getId(),
                authorId,
                author.getUsername(),
                /* allowFollowersToken */ false);

        boolean isReanswer = parent != null;
        // Re-read the parent so the broadcast carries the post-increment count.
        Long replyCount = isReanswer
                ? answerRepository.findById(parent.getId())
                        .map(QuestionAnswer::getReplyCount).orElse(null)
                : 0L;
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(isReanswer ? QnaRealtimeEventType.REANSWER_CREATED
                                      : QnaRealtimeEventType.ANSWER_CREATED)
                .questionId(question.getId())
                .actorId(authorId)
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .answerId(answer.getId())
                .parentAnswerId(isReanswer ? parent.getId() : null)
                .body(answer.getBody())
                .questionAnswerCount(question.getAnswerCount())
                .answerReplyCount(replyCount)
                .build());

        return mapper.toAnswerResponse(answer);
    }

    @Override
    @Transactional
    public QuestionAnswerResponse addAnswerWithMedia(UUID questionId, CreateAnswerRequest request, UUID authorId,
                                                     MultipartFile media,
                                                     MultipartFile voice) {
        // One-shot upload that mirrors PostCommentService.addCommentWithMedia —
        // upload the media (or voice) to R2 and stamp the URLs onto the request
        // before delegating to addAnswer, so the rest of the answer pipeline
        // (block guards, mentions, realtime, dispatch) runs unchanged.
        if (media != null && !media.isEmpty()) {
            String prefix = "qna/" + questionId + "/answers/inline";
            String key = storageService.upload(media, prefix);
            String url = storageService.getPublicUrl(key);
            request.setMediaUrl(url);
            String contentType = media.getContentType();
            request.setMediaType(contentType != null && contentType.startsWith("video") ? "VIDEO" : "IMAGE");
        }
        if (voice != null && !voice.isEmpty()) {
            String prefix = "qna/" + questionId + "/answers/voice";
            String key = storageService.upload(voice, prefix);
            String url = storageService.getPublicUrl(key);
            request.setVoiceUrl(url);
        }
        return addAnswer(questionId, request, authorId);
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

        String previousBody = answer.getBody();
        answer.setBody(request.getBody().trim());
        answer.setEdited(true);
        answer.setEditedAt(LocalDateTime.now());
        answer.audit(AuditAction.UPDATE, "Edited answer");
        answer = answerRepository.save(answer);

        User actor = answer.getAuthor();
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(QnaRealtimeEventType.ANSWER_EDITED)
                .questionId(question.getId())
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .answerId(answer.getId())
                .parentAnswerId(answer.getParentAnswer() != null ? answer.getParentAnswer().getId() : null)
                .body(answer.getBody())
                .build());

        // Notify newly @-mentioned users introduced by this answer edit.
        mentionService.scanAndPublishDelta(
                previousBody,
                answer.getBody(),
                MentionSource.QUESTION_ANSWER,
                answer.getId(),
                question.getId(),
                requesterId,
                actor != null ? actor.getUsername() : null);

        return mapper.toAnswerResponse(answer);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionAnswerResponse> getAnswers(UUID questionId, Pageable pageable) {
        return getAnswers(questionId, null, pageable);
    }

    /**
     * Restriction-aware top-level answer listing. The viewer's own and the
     * question author's view always include every answer; everyone else has
     * answers from restricted authors filtered out.
     *
     * <p>Single SQL with EntityGraph fetch on author + parent, plus one batch
     * lookup of {@code myReaction} for the page — no N+1 round trips.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<QuestionAnswerResponse> getAnswers(UUID questionId, UUID requesterId, Pageable pageable) {
        findQuestionOrThrow(questionId);
        Page<QuestionAnswer> page = answerRepository.findVisibleTopLevelAnswers(
                questionId, requesterId, pageable);
        // replyCount comes from the denormalised column on the entity now —
        // no extra GROUP-BY query per page.
        Map<UUID, AnswerReactionType> mine = batchMyReactions(page.getContent(), requesterId);
        return page.map(a -> mapper.toAnswerResponse(a, mine.get(a.getId()), a.getReplyCount()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionAnswerResponse> getReanswers(UUID questionId, UUID answerId, Pageable pageable) {
        return getReanswers(questionId, answerId, null, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuestionAnswerResponse> getReanswers(UUID questionId, UUID answerId,
                                                     UUID requesterId, Pageable pageable) {
        findQuestionOrThrow(questionId);
        QuestionAnswer parent = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));
        Page<QuestionAnswer> page = answerRepository.findVisibleReanswers(
                parent.getId(), requesterId, pageable);
        Map<UUID, AnswerReactionType> mine = batchMyReactions(page.getContent(), requesterId);
        return page.map(a -> mapper.toAnswerResponse(a, mine.get(a.getId()), 0L));
    }

    @Override
    @Transactional
    public void deleteQuestion(UUID questionId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("You can only delete your own question");
        }

        question.setDeletedAt(LocalDateTime.now());
        question.setStatus(ak.dev.irc.app.qna.enums.QuestionStatus.ARCHIVED);
        questionRepository.save(question);

        User author = question.getAuthor();
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(QnaRealtimeEventType.QUESTION_DELETED)
                .questionId(question.getId())
                .actorId(requesterId)
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .build());
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

        UUID parentId = answer.getParentAnswer() != null ? answer.getParentAnswer().getId() : null;

        // Reanswers don't count toward the question's answerCount, so only adjust for top-level deletions.
        if (answer.getParentAnswer() == null) {
            long remainingAnswers = Math.max(0L, question.getAnswerCount() - 1);
            question.setAnswerCount(remainingAnswers);
            if (remainingAnswers == 0 && question.getStatus() == ak.dev.irc.app.qna.enums.QuestionStatus.ANSWERED) {
                question.setStatus(ak.dev.irc.app.qna.enums.QuestionStatus.OPEN);
            }
            questionRepository.save(question);
        } else {
            // Reanswer deletion drops the parent's denormalised counter.
            answerRepository.updateReplyCount(parentId, -1);
        }

        Long parentReplyCount = parentId != null
                ? answerRepository.findById(parentId)
                        .map(QuestionAnswer::getReplyCount).orElse(null)
                : null;
        User actor = userRepository.findById(requesterId).orElse(null);
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(QnaRealtimeEventType.ANSWER_DELETED)
                .questionId(question.getId())
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .answerId(answer.getId())
                .parentAnswerId(parentId)
                .questionAnswerCount(question.getAnswerCount())
                .answerReplyCount(parentReplyCount)
                .build());
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

        broadcastQuestionLifecycle(question, requesterId, QnaRealtimeEventType.QUESTION_LOCKED);
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

        broadcastQuestionLifecycle(question, requesterId, QnaRealtimeEventType.QUESTION_UNLOCKED);
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
    //  ACCEPT / UNACCEPT (multiple best answers supported)
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

        if (answer.getParentAnswer() != null) {
            throw new BadRequestException("Reanswers cannot be accepted as best answer", "REANSWER_NOT_ACCEPTABLE");
        }

        answer.setAccepted(true);
        answer.audit(AuditAction.UPDATE, "Answer accepted as best answer");
        answer = answerRepository.save(answer);

        eventPublisher.publishAnswerAccepted(question, answer);

        broadcastAnswerStatus(question, answer, requesterId, QnaRealtimeEventType.ANSWER_ACCEPTED);
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

        broadcastAnswerStatus(question, answer, requesterId, QnaRealtimeEventType.ANSWER_UNACCEPTED);
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

        eventPublisher.publishFeedbackAdded(question, answer, feedback);

        broadcastFeedback(question, answer, feedback, requesterId,
                QnaRealtimeEventType.ANSWER_FEEDBACK_ADDED);

        return mapper.toFeedbackResponse(feedback);
    }

    @Override
    @Transactional
    public AnswerFeedbackResponse editFeedback(UUID questionId, UUID answerId, UUID feedbackId,
                                                AddFeedbackRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        if (!canManageQuestion(question, requesterId)) {
            throw new ForbiddenException("Only the question author can edit feedback");
        }

        AnswerFeedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", "id", feedbackId));

        if (!feedback.getAnswer().getId().equals(answerId)) {
            throw new BadRequestException("Feedback does not belong to this answer", "FEEDBACK_MISMATCH");
        }
        if (!feedback.getAuthor().getId().equals(requesterId)) {
            throw new ForbiddenException("You can only edit your own feedback");
        }

        if (request.getFeedbackType() != null) feedback.setFeedbackType(request.getFeedbackType());
        if (request.getBody() != null)         feedback.setBody(request.getBody().trim());

        feedback.audit(AuditAction.UPDATE, "Feedback updated");
        feedback = feedbackRepository.save(feedback);

        broadcastFeedback(question, feedback.getAnswer(), feedback, requesterId,
                QnaRealtimeEventType.ANSWER_FEEDBACK_EDITED);
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

        QuestionAnswer answer = feedback.getAnswer();
        feedbackRepository.delete(feedback);

        User actor = userRepository.findById(requesterId).orElse(null);
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(QnaRealtimeEventType.ANSWER_FEEDBACK_DELETED)
                .questionId(question.getId())
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .answerId(answer.getId())
                .feedbackId(feedbackId)
                .build());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ATTACHMENTS (upload files to answer)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public AnswerAttachmentResponse uploadAttachment(UUID questionId, UUID answerId, MultipartFile file,
                                                      String caption, Integer displayOrder, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only upload attachments to your own answer");
        }

        String prefix = "qna/" + questionId + "/answers/" + answerId + "/attachments";
        String s3Key = storageService.upload(file, prefix);
        String fileUrl = storageService.getPublicUrl(s3Key);

        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        MediaType mediaType = resolveMediaType(mimeType);

        AnswerAttachment attachment = AnswerAttachment.builder()
                .answer(answer)
                .fileUrl(fileUrl)
                .s3Key(s3Key)
                .originalFileName(file.getOriginalFilename())
                .mimeType(mimeType)
                .mediaType(mediaType)
                .fileSize(file.getSize())
                .displayOrder(displayOrder != null ? displayOrder : 0)
                .caption(caption)
                .build();

        attachment = attachmentRepository.save(attachment);
        return mapper.toAttachmentResponse(attachment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnswerAttachmentResponse> getAttachments(UUID questionId, UUID answerId) {
        findQuestionOrThrow(questionId);
        return attachmentRepository.findByAnswerIdOrderByDisplayOrderAsc(answerId).stream()
                .map(mapper::toAttachmentResponse)
                .toList();
    }

    @Override
    @Transactional
    public AnswerAttachmentResponse updateAttachment(UUID questionId, UUID answerId, UUID attachmentId,
                                                      UpdateAnswerAttachmentRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only edit attachments on your own answer");
        }

        AnswerAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", "id", attachmentId));

        if (!attachment.getAnswer().getId().equals(answerId)) {
            throw new BadRequestException("Attachment does not belong to this answer", "ATTACHMENT_MISMATCH");
        }

        if (request.getCaption() != null) {
            attachment.setCaption(request.getCaption().isBlank() ? null : request.getCaption().trim());
        }
        if (request.getDisplayOrder() != null) {
            attachment.setDisplayOrder(request.getDisplayOrder());
        }

        attachment.audit(AuditAction.UPDATE, "Attachment metadata updated");
        attachment = attachmentRepository.save(attachment);
        return mapper.toAttachmentResponse(attachment);
    }

    @Override
    @Transactional
    public void deleteAttachment(UUID questionId, UUID answerId, UUID attachmentId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only delete attachments from your own answer");
        }

        AnswerAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", "id", attachmentId));

        if (!attachment.getAnswer().getId().equals(answerId)) {
            throw new BadRequestException("Attachment does not belong to this answer", "ATTACHMENT_MISMATCH");
        }

        storageService.delete(attachment.getS3Key());
        attachmentRepository.delete(attachment);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SOURCES / REFERENCES
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public AnswerSourceResponse addSource(UUID questionId, UUID answerId,
                                           CreateAnswerSourceRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only add sources to your own answer");
        }

        int nextOrder = answer.getSources() != null ? answer.getSources().size() : 0;

        AnswerSource source = AnswerSource.builder()
                .answer(answer)
                .sourceType(request.getSourceType())
                .title(request.getTitle().trim())
                .citationText(request.getCitationText() != null ? request.getCitationText().trim() : null)
                .url(request.getUrl())
                .doi(request.getDoi())
                .isbn(request.getIsbn())
                .displayOrder(nextOrder)
                .build();

        source = sourceRepository.save(source);
        return mapper.toSourceResponse(source);
    }

    @Override
    @Transactional
    public AnswerSourceResponse updateSource(UUID questionId, UUID answerId, UUID sourceId,
                                              UpdateAnswerSourceRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only edit sources on your own answer");
        }

        AnswerSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Source", "id", sourceId));

        if (!source.getAnswer().getId().equals(answerId)) {
            throw new BadRequestException("Source does not belong to this answer", "SOURCE_MISMATCH");
        }

        if (request.getSourceType() != null) source.setSourceType(request.getSourceType());
        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new BadRequestException("Source title cannot be empty", "EMPTY_TITLE");
            }
            source.setTitle(request.getTitle().trim());
        }
        if (request.getCitationText() != null) {
            source.setCitationText(request.getCitationText().isBlank() ? null : request.getCitationText().trim());
        }
        if (request.getUrl() != null) source.setUrl(request.getUrl().isBlank() ? null : request.getUrl().trim());
        if (request.getDoi() != null) source.setDoi(request.getDoi().isBlank() ? null : request.getDoi().trim());
        if (request.getIsbn() != null) source.setIsbn(request.getIsbn().isBlank() ? null : request.getIsbn().trim());
        if (request.getDisplayOrder() != null) source.setDisplayOrder(request.getDisplayOrder());

        source.audit(AuditAction.UPDATE, "Source updated");
        source = sourceRepository.save(source);
        return mapper.toSourceResponse(source);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnswerSourceResponse> getSources(UUID questionId, UUID answerId) {
        findQuestionOrThrow(questionId);
        return sourceRepository.findByAnswerIdOrderByDisplayOrderAsc(answerId).stream()
                .map(mapper::toSourceResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteSource(UUID questionId, UUID answerId, UUID sourceId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        if (!canManageAnswer(question, answer, requesterId)) {
            throw new ForbiddenException("You can only delete sources from your own answer");
        }

        AnswerSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Source", "id", sourceId));

        if (!source.getAnswer().getId().equals(answerId)) {
            throw new BadRequestException("Source does not belong to this answer", "SOURCE_MISMATCH");
        }

        // If the source has an uploaded file, delete it from S3
        if (source.getS3Key() != null) {
            storageService.delete(source.getS3Key());
        }

        sourceRepository.delete(source);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REACTIONS (apply to top-level answers AND reanswers)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public QuestionAnswerResponse reactToAnswer(UUID questionId, UUID answerId,
                                                 ReactToAnswerRequest request, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        // Block guards — refuse to react across any block edge with either the
        // answer author or the question author.
        socialGuard.requireNotBlockedBetween(
                requesterId, answer.getAuthor().getId(), "ANSWER_REACTION_BLOCKED_RELATIONSHIP");
        socialGuard.requireNotBlockedBetween(
                requesterId, question.getAuthor().getId(), "ANSWER_REACTION_BLOCKED_RELATIONSHIP");

        User user = userRepository.findById(requesterId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", requesterId));

        var existingOpt = reactionRepository.findByAnswerIdAndUserId(answerId, requesterId);
        boolean isChange = existingOpt.isPresent();
        AnswerReactionType previous = existingOpt.map(AnswerReaction::getReactionType).orElse(null);

        if (isChange) {
            AnswerReaction existing = existingOpt.get();
            existing.setReactionType(request.getReactionType());
            reactionRepository.save(existing);
        } else {
            AnswerReaction reaction = AnswerReaction.builder()
                    .id(new AnswerReactionId(answerId, requesterId))
                    .answer(answer)
                    .user(user)
                    .reactionType(request.getReactionType())
                    .build();
            reactionRepository.save(reaction);
            answer.incrementReactions();
            answerRepository.save(answer);
        }

        // Notification + activity recording.
        eventPublisher.publishAnswerReacted(question, answer, user, request.getReactionType().name());

        // Realtime — broadcast on the question's stream so every viewer sees
        // the count change live.
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(isChange ? QnaRealtimeEventType.ANSWER_REACTION_CHANGED
                                    : QnaRealtimeEventType.ANSWER_REACTION_ADDED)
                .questionId(question.getId())
                .actorId(requesterId)
                .actorUsername(user.getUsername())
                .actorAvatarUrl(user.getProfileImage())
                .answerId(answer.getId())
                .parentAnswerId(answer.getParentAnswer() != null ? answer.getParentAnswer().getId() : null)
                .reactionType(request.getReactionType().name())
                .previousReactionType(previous != null ? previous.name() : null)
                .answerReactionCount(answer.getReactionCount())
                .build());

        return mapper.toAnswerResponse(answer, request.getReactionType(), null);
    }

    @Override
    @Transactional
    public void removeAnswerReaction(UUID questionId, UUID answerId, UUID requesterId) {
        Question question = findQuestionOrThrow(questionId);
        QuestionAnswer answer = answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));

        reactionRepository.findByAnswerIdAndUserId(answerId, requesterId).ifPresent(r -> {
            AnswerReactionType previous = r.getReactionType();
            reactionRepository.delete(r);
            answer.decrementReactions();
            answerRepository.save(answer);

            User actor = userRepository.findById(requesterId).orElse(null);
            realtime.broadcast(QnaRealtimeEvent.builder()
                    .eventType(QnaRealtimeEventType.ANSWER_REACTION_REMOVED)
                    .questionId(question.getId())
                    .actorId(requesterId)
                    .actorUsername(actor != null ? actor.getUsername() : null)
                    .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                    .answerId(answer.getId())
                    .parentAnswerId(answer.getParentAnswer() != null ? answer.getParentAnswer().getId() : null)
                    .previousReactionType(previous.name())
                    .answerReactionCount(answer.getReactionCount())
                    .build());
        });
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

    private void broadcastQuestionLifecycle(Question question, UUID actorId, QnaRealtimeEventType type) {
        User author = question.getAuthor();
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(type)
                .questionId(question.getId())
                .actorId(actorId)
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .build());
    }

    private void broadcastAnswerStatus(Question question, QuestionAnswer answer,
                                        UUID actorId, QnaRealtimeEventType type) {
        User actor = userRepository.findById(actorId).orElse(null);
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(type)
                .questionId(question.getId())
                .actorId(actorId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .answerId(answer.getId())
                .parentAnswerId(answer.getParentAnswer() != null ? answer.getParentAnswer().getId() : null)
                .build());
    }

    private void broadcastFeedback(Question question, QuestionAnswer answer,
                                    AnswerFeedback feedback, UUID actorId,
                                    QnaRealtimeEventType type) {
        User actor = feedback.getAuthor();
        realtime.broadcast(QnaRealtimeEvent.builder()
                .eventType(type)
                .questionId(question.getId())
                .actorId(actorId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .answerId(answer.getId())
                .feedbackId(feedback.getId())
                .feedbackType(feedback.getFeedbackType() != null ? feedback.getFeedbackType().name() : null)
                .body(feedback.getBody())
                .build());
    }

    /**
     * Single-query lookup of {@code (answerId → reactionType)} for the page —
     * avoids N round trips when the viewer wants {@code myReaction} per answer.
     */
    private Map<UUID, AnswerReactionType> batchMyReactions(List<QuestionAnswer> answers, UUID requesterId) {
        if (requesterId == null || answers == null || answers.isEmpty()) return Map.of();
        List<UUID> ids = answers.stream().map(QuestionAnswer::getId).toList();
        Map<UUID, AnswerReactionType> map = new HashMap<>();
        for (Object[] row : answerRepository.findMyReactionsForAnswers(requesterId, ids)) {
            map.put((UUID) row[0], (AnswerReactionType) row[1]);
        }
        return map;
    }

    private static String joinForMention(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private MediaType resolveMediaType(String mimeType) {
        if (mimeType == null) return MediaType.OTHER;

        String lower = mimeType.toLowerCase();
        if (lower.startsWith("image/"))       return MediaType.IMAGE;
        if (lower.startsWith("video/"))       return MediaType.VIDEO;
        if (lower.startsWith("audio/"))       return MediaType.AUDIO;
        if (lower.equals("application/pdf"))  return MediaType.DOCUMENT;
        if (lower.contains("wordprocessingml") || lower.contains("msword"))
            return MediaType.DOCUMENT;
        if (lower.contains("presentationml") || lower.contains("powerpoint"))
            return MediaType.DOCUMENT;
        if (lower.contains("spreadsheetml") || lower.contains("excel") || lower.equals("text/csv"))
            return MediaType.SPREADSHEET;
        if (lower.equals("application/zip") || lower.contains("tar") || lower.contains("rar")
                || lower.contains("7z") || lower.contains("gzip"))
            return MediaType.ARCHIVE;
        return MediaType.OTHER;
    }
}
