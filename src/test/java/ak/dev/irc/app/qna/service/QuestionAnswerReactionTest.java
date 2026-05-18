package ak.dev.irc.app.qna.service;

import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.qna.dto.request.ReactToAnswerRequest;
import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import ak.dev.irc.app.qna.entity.AnswerReaction;
import ak.dev.irc.app.qna.entity.AnswerReactionId;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.qna.mapper.QuestionMapper;
import ak.dev.irc.app.qna.realtime.QnaRealtimeBroadcaster;
import ak.dev.irc.app.qna.realtime.QnaRealtimeEvent;
import ak.dev.irc.app.qna.realtime.QnaRealtimeEventType;
import ak.dev.irc.app.qna.realtime.QuestionViewTracker;
import ak.dev.irc.app.qna.repository.AnswerAttachmentRepository;
import ak.dev.irc.app.qna.repository.AnswerFeedbackRepository;
import ak.dev.irc.app.qna.repository.AnswerReactionRepository;
import ak.dev.irc.app.qna.repository.AnswerSourceRepository;
import ak.dev.irc.app.qna.repository.BestAnswerVoteRepository;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.qna.service.impl.QuestionServiceImpl;
import ak.dev.irc.app.rabbitmq.publisher.QuestionEventPublisher;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the QnA answer reaction flow. Verifies the
 * unlike → re-like → unlike sequence on an answer with 2 reactions yields
 * 2 → 1 → 2 → 1 (NOT 2 → 1 → 1 → 0), and that the unlike endpoints now
 * return the updated answer payload so the front-end gets the new state
 * synchronously without an extra GET or relying on the SSE echo.
 */
@ExtendWith(MockitoExtension.class)
class QuestionAnswerReactionTest {

    @Mock private QuestionRepository questionRepository;
    @Mock private QuestionAnswerRepository answerRepository;
    @Mock private AnswerFeedbackRepository feedbackRepository;
    @Mock private AnswerAttachmentRepository attachmentRepository;
    @Mock private AnswerSourceRepository sourceRepository;
    @Mock private AnswerReactionRepository reactionRepository;
    @Mock private BestAnswerVoteRepository bestAnswerVoteRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserFollowRepository followRepository;
    @Mock private UserBlockRepository blockRepository;
    @Mock private QuestionMapper mapper;
    @Mock private QuestionEventPublisher eventPublisher;
    @Mock private S3StorageService storageService;
    @Mock private MentionService mentionService;
    @Mock private SocialGuard socialGuard;
    @Mock private FollowingIdsCache followingIdsCache;
    @Mock private QnaRealtimeBroadcaster realtime;
    @Mock private QuestionViewTracker viewTracker;
    @Mock private UserActivityService userActivityService;
    @Mock private CounterCache counterCache;
    @Mock private RateLimiter rateLimiter;
    @Mock private EntityManager entityManager;

    @InjectMocks private QuestionServiceImpl service;

    private UUID questionId;
    private UUID answerId;
    private UUID userId;
    private UUID authorId;
    private User actor;
    private User author;
    private Question question;
    private QuestionAnswer answer;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        questionId = UUID.randomUUID();
        answerId = UUID.randomUUID();
        userId = UUID.randomUUID();
        authorId = UUID.randomUUID();

        actor = User.builder().id(userId).email("actor@test").build();
        author = User.builder().id(authorId).email("author@test").build();
        question = Question.builder().id(questionId).author(author)
                .title("Why?").body("Body").status(QuestionStatus.OPEN).build();
        answer = QuestionAnswer.builder().id(answerId).question(question).author(author)
                .body("Because.").reactionCount(2L).replyCount(0L).build();
    }

    @Test
    @DisplayName("regression: unlike → re-like → unlike on an answer with 2 reactions yields 2→1→2→1, not 2→1→1→0; unreact returns updated DTO")
    void unlikeThenRelikeThenUnlike_keepsAnswerCountInSync() {
        // ── In-memory DB ─────────────────────────────────────────────────
        AtomicReference<AnswerReaction> dbRow = new AtomicReference<>(
                AnswerReaction.builder()
                        .id(new AnswerReactionId(answerId, userId))
                        .answer(answer).user(actor)
                        .reactionType(AnswerReactionType.LIKE)
                        .build());

        when(questionRepository.findByIdAndDeletedAtIsNull(questionId)).thenReturn(Optional.of(question));
        when(answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId))
                .thenReturn(Optional.of(answer));
        when(reactionRepository.findByAnswerIdAndUserId(answerId, userId))
                .thenAnswer(inv -> Optional.ofNullable(dbRow.get()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(mapper.toAnswerResponse(eq(answer), any(), any()))
                .thenAnswer(inv -> stubbedAnswerResponse(answer.getReactionCount(), inv.getArgument(1)));

        doAnswer(inv -> { dbRow.set(null); return null; })
                .when(reactionRepository).delete(any(AnswerReaction.class));
        doAnswer(inv -> {
                    dbRow.set(inv.getArgument(0));
                    return inv.getArgument(0);
                })
                .when(reactionRepository).save(any(AnswerReaction.class));
        doAnswer(inv -> {
                    long delta = inv.getArgument(1);
                    answer.setReactionCount(Math.max(0L, answer.getReactionCount() + delta));
                    return null;
                })
                .when(answerRepository).updateReactionCount(eq(answerId), any(Long.class));

        // ── 1) Unlike: 2 → 1, row gone ───────────────────────────────────
        QuestionAnswerResponse afterFirstUnlike = service.removeAnswerReaction(questionId, answerId, userId);
        assertThat(answer.getReactionCount()).as("after first unlike").isEqualTo(1L);
        assertThat(dbRow.get()).as("row deleted").isNull();
        assertThat(afterFirstUnlike.reactionCount()).isEqualTo(1L);
        assertThat(afterFirstUnlike.myReaction()).isNull();
        verify(reactionRepository).delete(any(AnswerReaction.class));
        verify(answerRepository).updateReactionCount(answerId, -1L);

        // ── 2) Re-like: 1 → 2, row inserted ──────────────────────────────
        QuestionAnswerResponse afterRelike = service.reactToAnswer(
                questionId, answerId, new ReactToAnswerRequest(), userId);
        assertThat(answer.getReactionCount())
                .as("re-like must increment to 2 — must NOT take idempotent path")
                .isEqualTo(2L);
        assertThat(dbRow.get()).as("re-like inserts a fresh row").isNotNull();
        assertThat(afterRelike.reactionCount()).isEqualTo(2L);
        assertThat(afterRelike.myReaction()).isEqualTo(AnswerReactionType.LIKE);
        verify(reactionRepository).save(any(AnswerReaction.class));
        verify(answerRepository).updateReactionCount(answerId, 1L);

        // ── 3) Unlike again: 2 → 1, row gone ─────────────────────────────
        QuestionAnswerResponse afterSecondUnlike = service.removeAnswerReaction(questionId, answerId, userId);
        assertThat(answer.getReactionCount())
                .as("second unlike from 2 must land on 1 (NOT 0)")
                .isEqualTo(1L);
        assertThat(dbRow.get()).isNull();
        assertThat(afterSecondUnlike.reactionCount()).isEqualTo(1L);
        assertThat(afterSecondUnlike.myReaction()).isNull();

        // delete called twice (once per real unlike), save called once (re-like)
        verify(reactionRepository, times(2)).delete(any(AnswerReaction.class));
        verify(reactionRepository).save(any(AnswerReaction.class));

        // Cache write-through carries the truthful count at every step
        ArgumentCaptor<Long> cached = ArgumentCaptor.forClass(Long.class);
        verify(counterCache, times(3)).set(eq(CounterCache.Kind.ANSWER), eq(answerId),
                eq(CounterCache.F_REACTIONS), cached.capture());
        assertThat(cached.getAllValues()).containsExactly(1L, 2L, 1L);

        // SSE broadcast carries the truthful count at every step
        ArgumentCaptor<QnaRealtimeEvent> sseEvents = ArgumentCaptor.forClass(QnaRealtimeEvent.class);
        verify(realtime, times(3)).broadcast(sseEvents.capture());
        assertThat(sseEvents.getAllValues())
                .extracting(QnaRealtimeEvent::getEventType, QnaRealtimeEvent::getAnswerReactionCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.ANSWER_REACTION_REMOVED, 1L),
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.ANSWER_REACTION_ADDED,   2L),
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.ANSWER_REACTION_REMOVED, 1L));

        // RabbitMQ events fire on real DB-changing branches
        verify(eventPublisher).publishAnswerReacted(eq(question), eq(answer), eq(actor), eq(AnswerReactionType.LIKE.name()));
        verify(eventPublisher, times(2)).publishAnswerUnreacted(eq(questionId), eq(answerId),
                eq(userId), eq(AnswerReactionType.LIKE.name()));
    }

    @Test
    @DisplayName("unlike when no reaction exists: no DB write, broadcast carries authoritative count, response has myReaction=null")
    void removeAnswerReaction_noExisting_idempotentBroadcastsCurrent() {
        when(questionRepository.findByIdAndDeletedAtIsNull(questionId)).thenReturn(Optional.of(question));
        when(answerRepository.findByIdAndQuestionIdAndDeletedAtIsNull(answerId, questionId))
                .thenReturn(Optional.of(answer));
        when(reactionRepository.findByAnswerIdAndUserId(answerId, userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(mapper.toAnswerResponse(eq(answer), any(), any()))
                .thenAnswer(inv -> stubbedAnswerResponse(answer.getReactionCount(), null));

        QuestionAnswerResponse out = service.removeAnswerReaction(questionId, answerId, userId);

        verify(reactionRepository, never()).delete(any(AnswerReaction.class));
        verify(answerRepository, never()).updateReactionCount(eq(answerId), any(Long.class));
        verify(eventPublisher, never()).publishAnswerUnreacted(any(), any(), any(), any());
        verify(counterCache).set(CounterCache.Kind.ANSWER, answerId, CounterCache.F_REACTIONS, 2L);
        assertThat(out.reactionCount()).isEqualTo(2L);
        assertThat(out.myReaction()).isNull();
    }

    private QuestionAnswerResponse stubbedAnswerResponse(long reactionCount, AnswerReactionType myReaction) {
        return new QuestionAnswerResponse(
                answerId, questionId, authorId, "author@test", null, "b.png",
                "Because.", /* parentAnswerId */ null, /* replyCount */ 0L,
                /* mediaUrl */ null, /* mediaType */ null, /* mediaThumbnailUrl */ null,
                /* voiceUrl */ null, /* voiceDurationSeconds */ null,
                /* links */ null,
                java.util.List.of(), java.util.List.of(),
                /* accepted */ false, /* isBestAnswer */ false, /* bestAnswerVoteCount */ 0L,
                /* votedByMe */ false,
                /* edited */ false, /* editedAt */ null,
                /* deleted */ false, /* deletedAt */ null,
                /* feedbackCount */ 0L,
                reactionCount, myReaction,
                /* createdAt */ null, /* updatedAt */ null,
                /* timeAgo */ null, /* formattedDate */ null
        );
    }
}
