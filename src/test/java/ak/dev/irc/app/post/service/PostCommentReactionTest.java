package ak.dev.irc.app.post.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostComment;
import ak.dev.irc.app.post.entity.PostCommentReaction;
import ak.dev.irc.app.post.entity.PostCommentReactionId;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.mapper.PostMapper;
import ak.dev.irc.app.post.realtime.PostRealtimeBroadcaster;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.repository.PostCommentReactionRepository;
import ak.dev.irc.app.post.repository.PostCommentRepository;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.rabbitmq.publisher.PostEventPublisher;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the comment reaction count drift the user reported:
 * starting at 2, the sequence unlike → re-like → unlike was producing
 * 2 → 1 → 1 → 0 instead of 2 → 1 → 2 → 1.
 *
 * <p>Root cause: PostComment.reactions was mapped @OneToMany cascade=ALL
 * orphanRemoval=true, but never read anywhere. em.refresh(comment) cascaded
 * into the collection and interfered with the queued repository delete on a
 * single PostCommentReaction row, leaving the row in the DB while the
 * reactionCount column still got decremented. The next "re-like" then took
 * the idempotent path (find returned the orphaned row) so the +1 was skipped.
 *
 * <p>The fix removed the collection mapping; reactions are managed
 * exclusively via PostCommentReactionRepository.
 */
@ExtendWith(MockitoExtension.class)
class PostCommentReactionTest {

    @Mock private PostRepository postRepository;
    @Mock private PostCommentRepository commentRepository;
    @Mock private PostCommentReactionRepository commentReactionRepository;
    @Mock private PostMapper postMapper;
    @Mock private PostEventPublisher eventPublisher;
    @Mock private UserRepository userRepository;
    @Mock private S3StorageService storageService;
    @Mock private MentionService mentionService;
    @Mock private PostRealtimeBroadcaster realtime;
    @Mock private SocialGuard socialGuard;
    @Mock private CounterCache counterCache;
    @Mock private RateLimiter rateLimiter;
    @Mock private EntityManager em;

    @InjectMocks private PostCommentService service;

    private UUID commentId;
    private UUID postId;
    private UUID userId;
    private User actor;
    private User author;
    private Post post;
    private PostComment comment;

    @BeforeEach
    void setUp() {
        // @PersistenceContext field is not in the Lombok constructor.
        ReflectionTestUtils.setField(service, "em", em);

        commentId = UUID.randomUUID();
        postId = UUID.randomUUID();
        userId = UUID.randomUUID();

        actor = User.builder().id(userId).email("actor@test").build();
        author = User.builder().id(UUID.randomUUID()).email("author@test").build();
        post = Post.builder().id(postId).author(author).postType(PostType.TEXT)
                .status(PostStatus.PUBLISHED).build();
        comment = PostComment.builder().id(commentId).post(post).author(author)
                .reactionCount(2L).replyCount(0L).build();
    }

    /**
     * Simulate the user's exact sequence: starting at 2, unlike → re-like →
     * unlike must yield 2 → 1 → 2 → 1, with the reaction row correctly
     * removed/recreated each time.
     */
    @Test
    @DisplayName("regression: unlike → re-like → unlike on a comment with 2 likes yields 2 → 1 → 2 → 1, not 2 → 1 → 1 → 0")
    void unlikeThenRelikeThenUnlike_keepsCountInSync() {
        // ── State machine simulating the DB row + count ──────────────────
        AtomicReference<PostCommentReaction> dbRow = new AtomicReference<>(
                PostCommentReaction.builder()
                        .id(new PostCommentReactionId(commentId, userId))
                        .comment(comment).user(actor)
                        .reactionType(PostReactionType.LIKE)
                        .build());

        when(commentReactionRepository.findByCommentIdAndUserId(commentId, userId))
                .thenAnswer(inv -> Optional.ofNullable(dbRow.get()));
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(userRepository.getReferenceById(userId)).thenReturn(actor);
        when(postMapper.toCommentResponse(eq(comment), any()))
                .thenAnswer(inv -> CommentResponse.builder()
                        .id(commentId)
                        .reactionCount(comment.getReactionCount())
                        .myReaction(inv.getArgument(1))
                        .build());

        // delete(r) drops the row from our simulated DB
        doAnswer(inv -> { dbRow.set(null); return null; })
                .when(commentReactionRepository).delete(any(PostCommentReaction.class));
        // save(reaction) inserts the row
        doAnswer(inv -> {
                    dbRow.set(inv.getArgument(0));
                    return inv.getArgument(0);
                })
                .when(commentReactionRepository).save(any(PostCommentReaction.class));
        // The JPQL +/- delta lands on the comment row
        doAnswer(inv -> {
                    long delta = inv.getArgument(1);
                    comment.setReactionCount(Math.max(0L, comment.getReactionCount() + delta));
                    return null;
                })
                .when(commentRepository).updateReactionCount(eq(commentId), any(Long.class));
        // em.refresh re-reads the entity — already in sync because the simulated
        // delta updates the in-memory comment directly.

        // ── 1) Unlike: 2 → 1, row gone ───────────────────────────────────
        CommentResponse afterFirstUnlike = service.removeCommentReaction(commentId, userId);
        assertThat(comment.getReactionCount()).as("after first unlike, count").isEqualTo(1L);
        assertThat(dbRow.get()).as("row should be deleted").isNull();
        assertThat(afterFirstUnlike.getReactionCount()).isEqualTo(1L);
        assertThat(afterFirstUnlike.getMyReaction()).isNull();
        verify(commentReactionRepository).delete(any(PostCommentReaction.class));
        verify(commentRepository).updateReactionCount(commentId, -1L);

        // ── 2) Re-like: 1 → 2, NEW row inserted (NOT idempotent path) ────
        CommentResponse afterRelike = service.reactToComment(commentId, userId, new ReactToPostRequest());
        assertThat(comment.getReactionCount())
                .as("re-like must increment to 2 — not stay at 1 (the original bug)")
                .isEqualTo(2L);
        assertThat(dbRow.get()).as("re-like must insert a fresh reaction row").isNotNull();
        assertThat(afterRelike.getReactionCount()).isEqualTo(2L);
        assertThat(afterRelike.getMyReaction()).isEqualTo(PostReactionType.LIKE);
        verify(commentReactionRepository).save(any(PostCommentReaction.class));
        verify(commentRepository).updateReactionCount(commentId, 1L);

        // ── 3) Unlike again: 2 → 1, row gone ─────────────────────────────
        CommentResponse afterSecondUnlike = service.removeCommentReaction(commentId, userId);
        assertThat(comment.getReactionCount())
                .as("second unlike from 2 must land on 1 — not 0 (the original bug)")
                .isEqualTo(1L);
        assertThat(dbRow.get()).as("second unlike removes the row").isNull();
        assertThat(afterSecondUnlike.getReactionCount()).isEqualTo(1L);
        assertThat(afterSecondUnlike.getMyReaction()).isNull();

        // delete called once per real unlike (2 total)
        verify(commentReactionRepository, times(2)).delete(any(PostCommentReaction.class));
        // save called once for the re-like
        verify(commentReactionRepository).save(any(PostCommentReaction.class));

        // Cache write-through carries the truthful count at every step
        ArgumentCaptor<Long> cached = ArgumentCaptor.forClass(Long.class);
        verify(counterCache, times(3)).set(eq(CounterCache.Kind.POST_COMMENT), eq(commentId),
                eq(CounterCache.F_REACTIONS), cached.capture());
        assertThat(cached.getAllValues()).containsExactly(1L, 2L, 1L);

        // SSE broadcast carries the truthful count at every step
        ArgumentCaptor<PostRealtimeEvent> sseEvents = ArgumentCaptor.forClass(PostRealtimeEvent.class);
        verify(realtime, times(3)).broadcast(sseEvents.capture());
        assertThat(sseEvents.getAllValues())
                .extracting(PostRealtimeEvent::getEventType, PostRealtimeEvent::getCommentReactionCount)
                .containsExactly(
                        tuple(PostRealtimeEventType.COMMENT_REACTION_REMOVED, 1L),
                        tuple(PostRealtimeEventType.COMMENT_REACTION_ADDED,   2L),
                        tuple(PostRealtimeEventType.COMMENT_REACTION_REMOVED, 1L));

        // No accidental publishCommentReacted on the (already-present) row paths
        verify(eventPublisher).publishCommentReacted(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static org.mockito.verification.VerificationMode times(int n) {
        return org.mockito.Mockito.times(n);
    }
}
