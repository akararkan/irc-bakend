package ak.dev.irc.app.post.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostSave;
import ak.dev.irc.app.post.entity.PostSaveId;
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
 * Save-flow regression contract for posts:
 *   save (count 0→1, isSaved true) → save again (idempotent, count stays 1)
 *   → unsave (1→0, isSaved false) → unsave again (idempotent, stays 0).
 * Both endpoints must return the updated PostResponse so the front-end never
 * needs to re-GET to see the new state.
 */
@ExtendWith(MockitoExtension.class)
class PostSaveTest {

    @Mock private PostRepository         postRepository;
    @Mock private PostReactionRepository reactionRepository;
    @Mock private PostShareRepository    shareRepository;
    @Mock private PostSaveRepository     saveRepository;
    @Mock private PostMapper             postMapper;
    @Mock private PostEventPublisher     eventPublisher;
    @Mock private UserRepository         userRepository;
    @Mock private UserFollowRepository   followRepository;
    @Mock private UserBlockRepository    blockRepository;
    @Mock private S3StorageService       storageService;
    @Mock private MentionService         mentionService;
    @Mock private PostRealtimeBroadcaster realtime;
    @Mock private PostViewTracker        viewTracker;
    @Mock private SocialGuard            socialGuard;
    @Mock private FollowingIdsCache      followingIdsCache;
    @Mock private CounterCache           counterCache;
    @Mock private RateLimiter            rateLimiter;
    @Mock private EntityManager          em;

    @InjectMocks private PostService service;

    private UUID postId;
    private UUID userId;
    private User actor;
    private User author;
    private Post post;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "self", service);

        postId = UUID.randomUUID();
        userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        actor  = User.builder().id(userId).email("actor@test").build();
        author = User.builder().id(authorId).email("author@test").build();
        post = Post.builder()
                .id(postId).author(author)
                .postType(PostType.TEXT).status(PostStatus.PUBLISHED)
                .reactionCount(0L).commentCount(0L).shareCount(0L).viewCount(0L).saveCount(0L)
                .build();
    }

    @Test
    @DisplayName("regression: save → re-save → unsave → re-unsave keeps saveCount at 0/1/1/0/0 with correct isSaved")
    void saveLifecycle_returnsUpdatedDtoAtEveryStep() {
        AtomicReference<PostSave> dbRow = new AtomicReference<>(null);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(saveRepository.existsById(any(PostSaveId.class)))
                .thenAnswer(inv -> dbRow.get() != null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(userRepository.getReferenceById(userId)).thenReturn(actor);
        when(reactionRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.empty());
        when(postMapper.toResponse(eq(post), any(), any(Boolean.class)))
                .thenAnswer(inv -> PostResponse.builder()
                        .id(postId)
                        .saveCount(post.getSaveCount())
                        .isSaved(inv.getArgument(2))
                        .build());

        doAnswer(inv -> {
                    dbRow.set(inv.getArgument(0));
                    return inv.getArgument(0);
                })
                .when(saveRepository).save(any(PostSave.class));
        doAnswer(inv -> { dbRow.set(null); return null; })
                .when(saveRepository).deleteById(any(PostSaveId.class));
        doAnswer(inv -> {
                    long delta = inv.getArgument(1);
                    post.setSaveCount(Math.max(0L, post.getSaveCount() + delta));
                    return null;
                })
                .when(postRepository).adjustSaveCount(eq(postId), any(Long.class));

        // ── 1) Save ──────────────────────────────────────────────────────
        PostResponse afterSave = service.savePost(postId, userId, "Default");
        assertThat(post.getSaveCount()).isEqualTo(1L);
        assertThat(dbRow.get()).isNotNull();
        assertThat(afterSave.getSaveCount()).isEqualTo(1L);
        assertThat(afterSave.isSaved()).isTrue();

        // ── 2) Save again — idempotent, same count, payload still says saved ─
        PostResponse afterReSave = service.savePost(postId, userId, "Default");
        assertThat(post.getSaveCount()).as("re-save must NOT double-count").isEqualTo(1L);
        assertThat(afterReSave.getSaveCount()).isEqualTo(1L);
        assertThat(afterReSave.isSaved()).isTrue();
        verify(saveRepository, times(1)).save(any(PostSave.class));

        // ── 3) Unsave ────────────────────────────────────────────────────
        PostResponse afterUnsave = service.unsavePost(postId, userId);
        assertThat(post.getSaveCount()).isEqualTo(0L);
        assertThat(dbRow.get()).isNull();
        assertThat(afterUnsave.getSaveCount()).isEqualTo(0L);
        assertThat(afterUnsave.isSaved()).isFalse();

        // ── 4) Unsave again — idempotent, count stays at 0, isSaved false ─
        PostResponse afterReUnsave = service.unsavePost(postId, userId);
        assertThat(post.getSaveCount()).as("re-unsave must NOT underflow").isEqualTo(0L);
        assertThat(afterReUnsave.getSaveCount()).isEqualTo(0L);
        assertThat(afterReUnsave.isSaved()).isFalse();
        verify(saveRepository, times(1)).deleteById(any(PostSaveId.class));

        // Cache write-through and SSE both carry truthful counts at every step
        ArgumentCaptor<Long> cached = ArgumentCaptor.forClass(Long.class);
        verify(counterCache, times(4)).set(eq(CounterCache.Kind.POST), eq(postId),
                eq(CounterCache.F_SAVES), cached.capture());
        assertThat(cached.getAllValues()).containsExactly(1L, 1L, 0L, 0L);

        ArgumentCaptor<PostRealtimeEvent> sse = ArgumentCaptor.forClass(PostRealtimeEvent.class);
        verify(realtime, times(4)).broadcast(sse.capture());
        assertThat(sse.getAllValues())
                .extracting(PostRealtimeEvent::getEventType, PostRealtimeEvent::getPostSaveCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PostRealtimeEventType.SAVE_COUNT_UPDATED, 1L),
                        org.assertj.core.groups.Tuple.tuple(PostRealtimeEventType.SAVE_COUNT_UPDATED, 1L),
                        org.assertj.core.groups.Tuple.tuple(PostRealtimeEventType.SAVE_COUNT_UPDATED, 0L),
                        org.assertj.core.groups.Tuple.tuple(PostRealtimeEventType.SAVE_COUNT_UPDATED, 0L));
    }

    @Test
    @DisplayName("save: block guard rejection short-circuits before any insert")
    void save_blockedRelationship_throws() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        doAnswer(inv -> { throw new ak.dev.irc.app.common.exception.ForbiddenException(
                "blocked", "SAVE_BLOCKED_RELATIONSHIP"); })
                .when(socialGuard).requireNotBlockedBetween(eq(userId), any(UUID.class), eq("SAVE_BLOCKED_RELATIONSHIP"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.savePost(postId, userId, "Default"))
                .isInstanceOf(ak.dev.irc.app.common.exception.ForbiddenException.class);

        verify(saveRepository, never()).save(any(PostSave.class));
        verify(postRepository, never()).adjustSaveCount(any(), any(Long.class));
        verify(realtime, never()).broadcast(any());
    }
}
