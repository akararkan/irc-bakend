package ak.dev.irc.app.research.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.rabbitmq.publisher.ResearchEventPublisher;
import ak.dev.irc.app.research.dto.request.ReactRequest;
import ak.dev.irc.app.research.dto.response.CommentResponse;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchComment;
import ak.dev.irc.app.research.entity.ResearchCommentReaction;
import ak.dev.irc.app.research.entity.ResearchCommentReactionId;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.research.mapper.ResearchMapper;
import ak.dev.irc.app.research.realtime.ResearchRealtimeBroadcaster;
import ak.dev.irc.app.research.realtime.ResearchRealtimeEvent;
import ak.dev.irc.app.research.realtime.ResearchRealtimeEventType;
import ak.dev.irc.app.research.realtime.ResearchViewTracker;
import ak.dev.irc.app.research.repository.ResearchCommentReactionRepository;
import ak.dev.irc.app.research.repository.ResearchCommentRepository;
import ak.dev.irc.app.research.repository.ResearchDownloadRepository;
import ak.dev.irc.app.research.repository.ResearchMediaRepository;
import ak.dev.irc.app.research.repository.ResearchReactionRepository;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.repository.ResearchSaveRepository;
import ak.dev.irc.app.research.repository.ResearchSourceRepository;
import ak.dev.irc.app.research.repository.ResearchTagRepository;
import ak.dev.irc.app.research.service.IrcIdentifierService;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.research.service.VideoMetadataExtractor;
import ak.dev.irc.app.research.service.impl.ResearchServiceImpl;
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

import java.util.List;
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
 * Regression test for the research-comment reaction flow.
 * Mirrors {@code PostCommentReactionTest}: the unlike → re-like → unlike
 * sequence on a comment with 2 likes must yield 2 → 1 → 2 → 1, and the
 * unreact endpoints must return the updated comment payload (not 204) so
 * the front-end gets the new state in one round-trip.
 */
@ExtendWith(MockitoExtension.class)
class ResearchCommentReactionTest {

    @Mock private ResearchRepository         researchRepo;
    @Mock private ResearchMediaRepository    mediaRepo;
    @Mock private ResearchSourceRepository   sourceRepo;
    @Mock private ResearchTagRepository      tagRepo;
    @Mock private ResearchCommentRepository  commentRepo;
    @Mock private ResearchCommentReactionRepository commentReactionRepo;
    @Mock private ResearchReactionRepository reactionRepo;
    @Mock private ResearchSaveRepository     saveRepo;
    @Mock private ResearchDownloadRepository downloadRepo;
    @Mock private UserRepository             userRepo;
    @Mock private UserFollowRepository       followRepo;
    @Mock private UserBlockRepository        blockRepo;
    @Mock private S3StorageService           s3;
    @Mock private VideoMetadataExtractor     videoMetadataExtractor;
    @Mock private ResearchMapper             mapper;
    @Mock private IrcIdentifierService       ircIdentifierService;
    @Mock private ResearchEventPublisher     researchEventPublisher;
    @Mock private MentionService             mentionService;
    @Mock private ResearchRealtimeBroadcaster researchRealtime;
    @Mock private SocialGuard                socialGuard;
    @Mock private CounterCache               counterCache;
    @Mock private RateLimiter                rateLimiter;
    @Mock private ResearchViewTracker        viewTracker;
    @Mock private EntityManager              entityManager;

    @InjectMocks private ResearchServiceImpl service;

    private UUID researchId;
    private UUID commentId;
    private UUID userId;
    private UUID researcherId;
    private User actor;
    private User researcher;
    private Research research;
    private ResearchComment comment;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        researchId = UUID.randomUUID();
        commentId = UUID.randomUUID();
        userId = UUID.randomUUID();
        researcherId = UUID.randomUUID();

        actor = User.builder().id(userId).email("actor@test").build();
        researcher = User.builder().id(researcherId).email("researcher@test").build();
        research = Research.builder().id(researchId).researcher(researcher).build();
        comment = ResearchComment.builder()
                .id(commentId).research(research).user(researcher)
                .content("Insightful.")
                .likeCount(2L).replyCount(0L)
                .build();
    }

    @Test
    @DisplayName("regression: unlike → re-like → unlike on a research comment with 2 likes yields 2→1→2→1; unreact returns updated DTO")
    void unlikeThenRelikeThenUnlike_keepsCommentCountInSync() {
        AtomicReference<ResearchCommentReaction> dbRow = new AtomicReference<>(
                ResearchCommentReaction.builder()
                        .id(new ResearchCommentReactionId(commentId, userId))
                        .comment(comment).user(actor)
                        .reactionType(ReactionType.LIKE)
                        .build());

        when(commentRepo.findById(commentId)).thenReturn(Optional.of(comment));
        when(commentReactionRepo.findByCommentIdAndUserId(commentId, userId))
                .thenAnswer(inv -> Optional.ofNullable(dbRow.get()));
        when(userRepo.findById(userId)).thenReturn(Optional.of(actor));
        when(userRepo.getReferenceById(userId)).thenReturn(actor);
        when(mapper.toCommentResponse(eq(comment), eq(false), any()))
                .thenAnswer(inv -> stubbedCommentResponse(comment.getLikeCount(), inv.getArgument(2)));

        doAnswer(inv -> { dbRow.set(null); return null; })
                .when(commentReactionRepo).delete(any(ResearchCommentReaction.class));
        doAnswer(inv -> {
                    dbRow.set(inv.getArgument(0));
                    return inv.getArgument(0);
                })
                .when(commentReactionRepo).save(any(ResearchCommentReaction.class));
        doAnswer(inv -> {
                    long delta = inv.getArgument(1);
                    comment.setLikeCount(Math.max(0L, comment.getLikeCount() + delta));
                    return null;
                })
                .when(commentRepo).updateLikeCount(eq(commentId), any(Long.class));

        // ── 1) Unlike: 2 → 1 ─────────────────────────────────────────────
        CommentResponse afterFirstUnlike = service.removeCommentReaction(researchId, commentId, userId);
        assertThat(comment.getLikeCount()).isEqualTo(1L);
        assertThat(dbRow.get()).isNull();
        assertThat(afterFirstUnlike.likeCount()).isEqualTo(1L);
        assertThat(afterFirstUnlike.myReaction()).isNull();

        // ── 2) Re-like: 1 → 2 ────────────────────────────────────────────
        service.reactToComment(researchId, commentId, new ReactRequest(ReactionType.LIKE), userId);
        assertThat(comment.getLikeCount())
                .as("re-like must increment to 2 — not stay at 1")
                .isEqualTo(2L);
        assertThat(dbRow.get()).as("re-like inserts a fresh row").isNotNull();

        // ── 3) Unlike again: 2 → 1 ───────────────────────────────────────
        CommentResponse afterSecondUnlike = service.removeCommentReaction(researchId, commentId, userId);
        assertThat(comment.getLikeCount())
                .as("second unlike from 2 must land on 1 — not 0")
                .isEqualTo(1L);
        assertThat(dbRow.get()).isNull();
        assertThat(afterSecondUnlike.likeCount()).isEqualTo(1L);
        assertThat(afterSecondUnlike.myReaction()).isNull();

        verify(commentReactionRepo, times(2)).delete(any(ResearchCommentReaction.class));
        verify(commentReactionRepo).save(any(ResearchCommentReaction.class));

        ArgumentCaptor<Long> cached = ArgumentCaptor.forClass(Long.class);
        verify(counterCache, times(3)).set(eq(CounterCache.Kind.RESEARCH_COMMENT), eq(commentId),
                eq(CounterCache.F_REACTIONS), cached.capture());
        assertThat(cached.getAllValues()).containsExactly(1L, 2L, 1L);

        ArgumentCaptor<ResearchRealtimeEvent> evts = ArgumentCaptor.forClass(ResearchRealtimeEvent.class);
        verify(researchRealtime, times(3)).broadcast(evts.capture());
        assertThat(evts.getAllValues())
                .extracting(ResearchRealtimeEvent::getEventType, ResearchRealtimeEvent::getCommentLikeCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ResearchRealtimeEventType.COMMENT_REACTION_REMOVED, 1L),
                        org.assertj.core.groups.Tuple.tuple(ResearchRealtimeEventType.COMMENT_REACTION_ADDED,   2L),
                        org.assertj.core.groups.Tuple.tuple(ResearchRealtimeEventType.COMMENT_REACTION_REMOVED, 1L));
    }

    @Test
    @DisplayName("unlike when no reaction exists: no DB write, response carries authoritative count and myReaction=null")
    void removeCommentReaction_noExisting_returnsAuthoritativeState() {
        when(commentRepo.findById(commentId)).thenReturn(Optional.of(comment));
        when(commentReactionRepo.findByCommentIdAndUserId(commentId, userId)).thenReturn(Optional.empty());
        when(userRepo.findById(userId)).thenReturn(Optional.of(actor));
        when(mapper.toCommentResponse(eq(comment), eq(false), any()))
                .thenAnswer(inv -> stubbedCommentResponse(comment.getLikeCount(), null));

        CommentResponse out = service.removeCommentReaction(researchId, commentId, userId);

        verify(commentReactionRepo, never()).delete(any(ResearchCommentReaction.class));
        verify(commentRepo, never()).updateLikeCount(eq(commentId), any(Long.class));
        verify(counterCache).set(CounterCache.Kind.RESEARCH_COMMENT, commentId,
                CounterCache.F_REACTIONS, 2L);

        assertThat(out.likeCount()).isEqualTo(2L);
        assertThat(out.myReaction()).isNull();
    }

    private CommentResponse stubbedCommentResponse(long likeCount, ReactionType myReaction) {
        return new CommentResponse(
                commentId, researchId,
                userId, "Researcher", "researcher@test", "r.png",
                "Insightful.",
                /* mediaUrl */ null, /* mediaType */ null, /* mediaThumbnailUrl */ null,
                likeCount, /* replyCount */ 0L,
                myReaction,
                /* isEdited */ false, /* editedAt */ null,
                /* isHidden */ false, /* hiddenAt */ null,
                /* parentId */ null,
                List.of(),
                /* createdAt */ null, /* timeAgo */ null, /* formattedDate */ null
        );
    }
}
