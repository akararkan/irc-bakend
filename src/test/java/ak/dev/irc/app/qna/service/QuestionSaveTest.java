package ak.dev.irc.app.qna.service;

import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.qna.dto.response.QuestionResponse;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionSave;
import ak.dev.irc.app.qna.entity.QuestionSaveId;
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
import ak.dev.irc.app.qna.repository.QuestionSaveRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QnA save flow regression: save → re-save → unsave → re-unsave keeps the
 * counter at 1/1/0/0 and returns the updated QuestionResponse with the
 * correct {@code isSaved} flag at every step.
 */
@ExtendWith(MockitoExtension.class)
class QuestionSaveTest {

    @Mock private QuestionRepository questionRepository;
    @Mock private QuestionAnswerRepository answerRepository;
    @Mock private AnswerFeedbackRepository feedbackRepository;
    @Mock private AnswerAttachmentRepository attachmentRepository;
    @Mock private AnswerSourceRepository sourceRepository;
    @Mock private AnswerReactionRepository reactionRepository;
    @Mock private BestAnswerVoteRepository bestAnswerVoteRepository;
    @Mock private QuestionSaveRepository saveRepository;
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
    private UUID userId;
    private User actor;
    private Question question;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        questionId = UUID.randomUUID();
        userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        actor = User.builder().id(userId).email("actor@test").build();
        User author = User.builder().id(authorId).email("author@test").build();
        question = Question.builder()
                .id(questionId).author(author)
                .title("?").body(".").status(QuestionStatus.OPEN)
                .answerCount(0L).viewCount(0L).saveCount(0L)
                .build();
    }

    @Test
    @DisplayName("regression: save → re-save → unsave → re-unsave on a question keeps saveCount + isSaved truthful")
    void saveLifecycle_returnsUpdatedDtoAtEveryStep() {
        AtomicReference<QuestionSave> dbRow = new AtomicReference<>(null);

        when(questionRepository.findByIdAndDeletedAtIsNull(questionId)).thenReturn(Optional.of(question));
        when(saveRepository.existsById(any(QuestionSaveId.class)))
                .thenAnswer(inv -> dbRow.get() != null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(userRepository.getReferenceById(userId)).thenReturn(actor);
        when(mapper.toQuestionResponse(eq(question), any(Boolean.class)))
                .thenAnswer(inv -> stubResponse(question.getSaveCount(), inv.getArgument(1)));

        doAnswer(inv -> {
                    dbRow.set(inv.getArgument(0));
                    return inv.getArgument(0);
                })
                .when(saveRepository).save(any(QuestionSave.class));
        doAnswer(inv -> { dbRow.set(null); return null; })
                .when(saveRepository).deleteById(any(QuestionSaveId.class));
        doAnswer(inv -> {
                    long delta = inv.getArgument(1);
                    question.setSaveCount(Math.max(0L, question.getSaveCount() + delta));
                    return null;
                })
                .when(questionRepository).adjustSaveCount(eq(questionId), any(Long.class));

        // ── 1) Save (0 → 1, isSaved=true) ────────────────────────────────
        QuestionResponse afterSave = service.saveQuestion(questionId, userId, "Default");
        assertThat(question.getSaveCount()).isEqualTo(1L);
        assertThat(dbRow.get()).isNotNull();
        assertThat(afterSave.saveCount()).isEqualTo(1L);
        assertThat(afterSave.isSaved()).isTrue();

        // ── 2) Re-save (idempotent — count stays 1, payload still says saved)
        QuestionResponse afterReSave = service.saveQuestion(questionId, userId, "Default");
        assertThat(question.getSaveCount()).as("re-save must NOT double-count").isEqualTo(1L);
        assertThat(afterReSave.saveCount()).isEqualTo(1L);
        assertThat(afterReSave.isSaved()).isTrue();
        verify(saveRepository, times(1)).save(any(QuestionSave.class));

        // ── 3) Unsave (1 → 0, isSaved=false) ─────────────────────────────
        QuestionResponse afterUnsave = service.unsaveQuestion(questionId, userId);
        assertThat(question.getSaveCount()).isEqualTo(0L);
        assertThat(dbRow.get()).isNull();
        assertThat(afterUnsave.saveCount()).isEqualTo(0L);
        assertThat(afterUnsave.isSaved()).isFalse();

        // ── 4) Re-unsave (idempotent — count stays 0) ────────────────────
        QuestionResponse afterReUnsave = service.unsaveQuestion(questionId, userId);
        assertThat(question.getSaveCount()).as("re-unsave must NOT underflow").isEqualTo(0L);
        assertThat(afterReUnsave.saveCount()).isEqualTo(0L);
        assertThat(afterReUnsave.isSaved()).isFalse();
        verify(saveRepository, times(1)).deleteById(any(QuestionSaveId.class));

        // Cache and SSE both reflect the truth at every step
        ArgumentCaptor<Long> cached = ArgumentCaptor.forClass(Long.class);
        verify(counterCache, times(4)).set(eq(CounterCache.Kind.QUESTION), eq(questionId),
                eq(CounterCache.F_SAVES), cached.capture());
        assertThat(cached.getAllValues()).containsExactly(1L, 1L, 0L, 0L);

        ArgumentCaptor<QnaRealtimeEvent> sse = ArgumentCaptor.forClass(QnaRealtimeEvent.class);
        verify(realtime, times(4)).broadcast(sse.capture());
        assertThat(sse.getAllValues())
                .extracting(QnaRealtimeEvent::getEventType, QnaRealtimeEvent::getQuestionSaveCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.SAVE_COUNT_UPDATED, 1L),
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.SAVE_COUNT_UPDATED, 1L),
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.SAVE_COUNT_UPDATED, 0L),
                        org.assertj.core.groups.Tuple.tuple(QnaRealtimeEventType.SAVE_COUNT_UPDATED, 0L));
    }

    private QuestionResponse stubResponse(long saveCount, boolean isSaved) {
        return new QuestionResponse(
                questionId, UUID.randomUUID(), "author@test", "Author", "b.png",
                "?", ".", QuestionStatus.OPEN,
                /* answerCount */ 0L,
                /* viewCount */ 0L,
                saveCount,
                /* answersLocked */ false,
                /* maxAnswers */ null,
                isSaved,
                /* createdAt */ null, /* updatedAt */ null,
                /* timeAgo */ null, /* formattedDate */ null
        );
    }
}
