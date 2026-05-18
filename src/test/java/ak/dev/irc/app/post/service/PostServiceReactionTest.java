package ak.dev.irc.app.post.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.RateLimitExceededException;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostReaction;
import ak.dev.irc.app.post.entity.PostReactionId;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.mapper.PostMapper;
import ak.dev.irc.app.post.realtime.PostRealtimeBroadcaster;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostViewTracker;
import ak.dev.irc.app.post.repository.PostReactionRepository;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.post.repository.PostSaveRepository;
import ak.dev.irc.app.post.repository.PostShareRepository;
import ak.dev.irc.app.rabbitmq.event.post.PostReactedEvent;
import ak.dev.irc.app.rabbitmq.publisher.PostEventPublisher;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the post reaction flow. Covers every branch the user is
 * likely to hit: first like, idempotent re-like, unlike, unlike-when-absent,
 * and the rate-limit / block guards.
 *
 * <p>The reaction count contract being verified end-to-end is:
 * after each call, the response payload, the in-memory entity, the
 * write-through Redis hash, and the SSE broadcast all carry the same
 * post-mutation count.</p>
 */
@ExtendWith(MockitoExtension.class)
class PostServiceReactionTest {

    @Mock private PostRepository postRepository;
    @Mock private PostReactionRepository reactionRepository;
    @Mock private PostShareRepository shareRepository;
    @Mock private PostSaveRepository saveRepository;
    @Mock private PostMapper postMapper;
    @Mock private PostEventPublisher eventPublisher;
    @Mock private UserRepository userRepository;
    @Mock private UserFollowRepository followRepository;
    @Mock private UserBlockRepository blockRepository;
    @Mock private S3StorageService storageService;
    @Mock private MentionService mentionService;
    @Mock private PostRealtimeBroadcaster realtime;
    @Mock private PostViewTracker viewTracker;
    @Mock private SocialGuard socialGuard;
    @Mock private FollowingIdsCache followingIdsCache;
    @Mock private CounterCache counterCache;
    @Mock private RateLimiter rateLimiter;
    @Mock private EntityManager em;

    @InjectMocks private PostService service;

    private UUID postId;
    private UUID userId;
    private UUID authorId;
    private User actor;
    private User author;
    private Post post;

    @BeforeEach
    void setUp() {
        // @PersistenceContext / @Autowired fields are not in the Lombok constructor,
        // so @InjectMocks does not wire them — set explicitly.
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "self", service);
        postId = UUID.randomUUID();
        userId = UUID.randomUUID();
        authorId = UUID.randomUUID();

        actor = User.builder().id(userId).email("actor@test").build();
        author = User.builder().id(authorId).email("author@test").build();

        post = Post.builder()
                .id(postId)
                .author(author)
                .postType(PostType.TEXT)
                .status(PostStatus.PUBLISHED)
                .reactionCount(5L)
                .commentCount(0L)
                .shareCount(0L)
                .viewCount(0L)
                .saveCount(0L)
                .build();
    }

    // ── react: first time ────────────────────────────────────────────────

    @Test
    @DisplayName("react: first like creates row, +1 increments count, write-through cache, SSE carries new count")
    void react_firstLike_incrementsAndBroadcasts() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(reactionRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(userRepository.getReferenceById(userId)).thenReturn(actor);
        // Simulate the JPQL UPDATE landing on the row, then em.refresh pulling the new value.
        doAnswer(inv -> { post.setReactionCount(6L); return null; })
                .when(em).refresh(post);
        when(postMapper.toResponse(post, PostReactionType.LIKE))
                .thenReturn(PostResponse.builder().id(postId).reactionCount(6L).myReaction(PostReactionType.LIKE).build());

        PostResponse out = service.reactToPost(postId, userId, new ReactToPostRequest());

        // DB writes happened
        verify(reactionRepository).save(any(PostReaction.class));
        verify(postRepository).updateReactionCount(postId, 1);

        // Cache write-through carries post-increment value (6, not 5)
        verify(counterCache).set(CounterCache.Kind.POST, postId, CounterCache.F_REACTIONS, 6L);

        // RabbitMQ event fired exactly once
        ArgumentCaptor<PostReactedEvent> evt = ArgumentCaptor.forClass(PostReactedEvent.class);
        verify(eventPublisher).publishPostReacted(evt.capture());
        assertThat(evt.getValue().getPostId()).isEqualTo(postId);
        assertThat(evt.getValue().getReactorId()).isEqualTo(userId);
        assertThat(evt.getValue().getReactionType()).isEqualTo(PostReactionType.LIKE.name());

        // SSE broadcast carries the post-increment count
        ArgumentCaptor<PostRealtimeEvent> sse = ArgumentCaptor.forClass(PostRealtimeEvent.class);
        verify(realtime).broadcast(sse.capture());
        assertThat(sse.getValue().getEventType()).isEqualTo(PostRealtimeEventType.REACTION_ADDED);
        assertThat(sse.getValue().getPostReactionCount()).isEqualTo(6L);
        assertThat(sse.getValue().getActorId()).isEqualTo(userId);

        // Response carries the new count and myReaction = LIKE
        assertThat(out.getReactionCount()).isEqualTo(6L);
        assertThat(out.getMyReaction()).isEqualTo(PostReactionType.LIKE);
    }

    // ── react: idempotent re-like ────────────────────────────────────────

    @Test
    @DisplayName("react: re-like is a no-op at DB layer, broadcast carries authoritative current count")
    void react_alreadyReacted_idempotentNoIncrement() {
        PostReaction existing = PostReaction.builder()
                .id(new PostReactionId(postId, userId))
                .post(post).user(actor)
                .reactionType(PostReactionType.LIKE)
                .build();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(reactionRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        // refresh() returns the same value — count was 5 and stays 5
        when(postMapper.toResponse(post, PostReactionType.LIKE))
                .thenReturn(PostResponse.builder().id(postId).reactionCount(5L).myReaction(PostReactionType.LIKE).build());

        PostResponse out = service.reactToPost(postId, userId, new ReactToPostRequest());

        // CRITICAL: no second insert, no double increment
        verify(reactionRepository, never()).save(any(PostReaction.class));
        verify(postRepository, never()).updateReactionCount(eq(postId), any(Long.class));
        verify(eventPublisher, never()).publishPostReacted(any());

        // Cache + broadcast still fire so an SSE-driven UI can reconcile
        verify(counterCache).set(CounterCache.Kind.POST, postId, CounterCache.F_REACTIONS, 5L);
        ArgumentCaptor<PostRealtimeEvent> sse = ArgumentCaptor.forClass(PostRealtimeEvent.class);
        verify(realtime).broadcast(sse.capture());
        assertThat(sse.getValue().getEventType()).isEqualTo(PostRealtimeEventType.REACTION_ADDED);
        assertThat(sse.getValue().getPostReactionCount()).isEqualTo(5L);

        assertThat(out.getReactionCount()).isEqualTo(5L);
        assertThat(out.getMyReaction()).isEqualTo(PostReactionType.LIKE);
    }

    // ── unreact: row exists ──────────────────────────────────────────────

    @Test
    @DisplayName("unreact: deletes row, -1 decrements count, write-through cache, SSE carries new count, response has myReaction=null")
    void removeReaction_existingReaction_decrementsAndBroadcasts() {
        PostReaction existing = PostReaction.builder()
                .id(new PostReactionId(postId, userId))
                .post(post).user(actor)
                .reactionType(PostReactionType.LIKE)
                .build();
        when(reactionRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.of(existing));
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        // Simulate the JPQL -1 landing then em.refresh pulling 4
        doAnswer(inv -> { post.setReactionCount(4L); return null; })
                .when(em).refresh(post);
        when(postMapper.toResponse(post, null))
                .thenReturn(PostResponse.builder().id(postId).reactionCount(4L).myReaction(null).build());

        PostResponse out = service.removeReaction(postId, userId);

        verify(reactionRepository).delete(existing);
        verify(postRepository).updateReactionCount(postId, -1);
        verify(counterCache).set(CounterCache.Kind.POST, postId, CounterCache.F_REACTIONS, 4L);
        verify(eventPublisher).publishPostUnreacted(postId, userId, PostReactionType.LIKE.name());

        ArgumentCaptor<PostRealtimeEvent> sse = ArgumentCaptor.forClass(PostRealtimeEvent.class);
        verify(realtime).broadcast(sse.capture());
        assertThat(sse.getValue().getEventType()).isEqualTo(PostRealtimeEventType.REACTION_REMOVED);
        assertThat(sse.getValue().getPostReactionCount()).isEqualTo(4L);
        assertThat(sse.getValue().getPreviousReactionType()).isEqualTo(PostReactionType.LIKE.name());

        assertThat(out.getReactionCount()).isEqualTo(4L);
        assertThat(out.getMyReaction()).isNull();
    }

    // ── unreact: no row to remove ────────────────────────────────────────

    @Test
    @DisplayName("unreact: when no row exists, no DB delete, but cache + broadcast carry authoritative current count (no underflow)")
    void removeReaction_noExistingReaction_idempotentBroadcastsCurrent() {
        when(reactionRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.empty());
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        // refresh leaves count at 5 (no DB change happened)
        when(postMapper.toResponse(post, null))
                .thenReturn(PostResponse.builder().id(postId).reactionCount(5L).myReaction(null).build());

        PostResponse out = service.removeReaction(postId, userId);

        // No delete, no decrement — count must not underflow
        verify(reactionRepository, never()).delete(any(PostReaction.class));
        verify(postRepository, never()).updateReactionCount(eq(postId), any(Long.class));
        verify(eventPublisher, never()).publishPostUnreacted(any(), any(), any());

        // But cache + SSE still reconcile to authoritative current value
        verify(counterCache).set(CounterCache.Kind.POST, postId, CounterCache.F_REACTIONS, 5L);
        ArgumentCaptor<PostRealtimeEvent> sse = ArgumentCaptor.forClass(PostRealtimeEvent.class);
        verify(realtime).broadcast(sse.capture());
        assertThat(sse.getValue().getEventType()).isEqualTo(PostRealtimeEventType.REACTION_REMOVED);
        assertThat(sse.getValue().getPostReactionCount()).isEqualTo(5L);

        assertThat(out.getReactionCount()).isEqualTo(5L);
        assertThat(out.getMyReaction()).isNull();
    }

    // ── guards ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("react: rate limiter rejection short-circuits before any DB read")
    void react_rateLimited_doesNothing() {
        doAnswer(inv -> { throw new RateLimitExceededException("reaction", 5); })
                .when(rateLimiter).checkReaction(userId);

        assertThatThrownBy(() -> service.reactToPost(postId, userId, new ReactToPostRequest()))
                .isInstanceOf(RateLimitExceededException.class);

        verify(postRepository, never()).findById(any());
        verify(reactionRepository, never()).save(any());
        verify(realtime, never()).broadcast(any());
    }

    @Test
    @DisplayName("react: block-relationship rejection short-circuits before any write")
    void react_blocked_throwsAndDoesNotMutate() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        doAnswer(inv -> { throw new ak.dev.irc.app.common.exception.ForbiddenException(
                "blocked", "REACTION_BLOCKED_RELATIONSHIP"); })
                .when(socialGuard).requireNotBlockedBetween(userId, authorId, "REACTION_BLOCKED_RELATIONSHIP");

        assertThatThrownBy(() -> service.reactToPost(postId, userId, new ReactToPostRequest()))
                .isInstanceOf(ak.dev.irc.app.common.exception.ForbiddenException.class);

        verify(reactionRepository, never()).save(any());
        verify(postRepository, never()).updateReactionCount(any(), any(Long.class));
        verify(realtime, never()).broadcast(any());
        verify(counterCache, never()).set(any(), any(), any(), any(Long.class));
    }
}
