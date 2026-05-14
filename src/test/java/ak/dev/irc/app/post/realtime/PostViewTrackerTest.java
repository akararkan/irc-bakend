package ak.dev.irc.app.post.realtime;

import ak.dev.irc.app.post.repository.PostViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the new persistent per-user view dedup contract:
 * <ul>
 *   <li>Authed user, same post: counts ONCE forever (subsequent calls return false).</li>
 *   <li>Authed user, different post: counts.</li>
 *   <li>Different authed users on same post: each counts once.</li>
 *   <li>Anonymous viewer: 1h Redis-window dedupe on the request fingerprint
 *       (the existing behaviour) — same IP twice = one count, different IPs
 *       both count.</li>
 *   <li>DB outage on the authed path falls back to the anon Redis dedupe so a
 *       view is never silently lost.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PostViewTrackerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private PostViewRepository postViewRepository;

    @InjectMocks private PostViewTracker tracker;

    private UUID postId;
    private UUID otherPostId;
    private UUID userId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        postId = UUID.randomUUID();
        otherPostId = UUID.randomUUID();
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
    }

    @Test
    @DisplayName("authed user views same post twice: counts ONCE forever (no second bump)")
    void sameAuthedUserSamePost_countsOnce() {
        // Simulate ON CONFLICT DO NOTHING on the (post, user) PK
        ConcurrentMap<String, Boolean> ledger = new ConcurrentHashMap<>();
        when(postViewRepository.tryRecord(any(UUID.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0) + "/" + inv.getArgument(1);
                    return ledger.putIfAbsent(key, true) == null ? 1 : 0;
                });

        assertThat(tracker.shouldCount(postId, userId, "anything")).as("first view").isTrue();
        assertThat(tracker.shouldCount(postId, userId, "anything")).as("same user, same post, same minute").isFalse();
        assertThat(tracker.shouldCount(postId, userId, "anything")).as("same user, same post, again").isFalse();

        verify(postViewRepository, times(3)).tryRecord(postId, userId);
        verify(redisTemplate, never()).opsForValue();   // authed path skips Redis entirely
    }

    @Test
    @DisplayName("two different authed users on same post: each counts once")
    void twoDifferentAuthedUsers_eachCountsOnce() {
        ConcurrentMap<String, Boolean> ledger = new ConcurrentHashMap<>();
        when(postViewRepository.tryRecord(any(UUID.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0) + "/" + inv.getArgument(1);
                    return ledger.putIfAbsent(key, true) == null ? 1 : 0;
                });

        assertThat(tracker.shouldCount(postId, userId,      "ip-1")).isTrue();
        assertThat(tracker.shouldCount(postId, otherUserId, "ip-2")).isTrue();
        assertThat(tracker.shouldCount(postId, userId,      "ip-3")).isFalse();
        assertThat(tracker.shouldCount(postId, otherUserId, "ip-4")).isFalse();
    }

    @Test
    @DisplayName("same authed user views different posts: each counts")
    void sameUserDifferentPosts_eachCounts() {
        ConcurrentMap<String, Boolean> ledger = new ConcurrentHashMap<>();
        when(postViewRepository.tryRecord(any(UUID.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0) + "/" + inv.getArgument(1);
                    return ledger.putIfAbsent(key, true) == null ? 1 : 0;
                });

        assertThat(tracker.shouldCount(postId,      userId, null)).isTrue();
        assertThat(tracker.shouldCount(otherPostId, userId, null)).isTrue();
        assertThat(tracker.shouldCount(postId,      userId, null)).isFalse();
    }

    @Test
    @DisplayName("anonymous viewer: same IP within 1h = one count; new IP = new count")
    void anonymous_redisDedup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ConcurrentMap<String, Boolean> hot = new ConcurrentHashMap<>();
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenAnswer(inv -> hot.putIfAbsent(inv.getArgument(0), true) == null);

        assertThat(tracker.shouldCount(postId, null, "192.168.1.1")).as("first anon view").isTrue();
        assertThat(tracker.shouldCount(postId, null, "192.168.1.1")).as("same IP within 1h").isFalse();
        assertThat(tracker.shouldCount(postId, null, "192.168.1.2")).as("different IP").isTrue();

        verify(postViewRepository, never()).tryRecord(any(), any());
    }

    @Test
    @DisplayName("ledger throws PK violation (concurrent first-view race) → returns false, no double-count")
    void concurrentRace_resolvesToSingleCount() {
        when(postViewRepository.tryRecord(postId, userId))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(tracker.shouldCount(postId, userId, "anything")).isFalse();
    }

    @Test
    @DisplayName("ledger DB outage on authed path → falls back to anon Redis dedupe (view never silently lost)")
    void ledgerOutage_fallsBackToRedis() {
        when(postViewRepository.tryRecord(postId, userId))
                .thenThrow(new RuntimeException("db down"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        assertThat(tracker.shouldCount(postId, userId, "192.168.1.1"))
                .as("first view during outage still counts")
                .isTrue();
    }

    @Test
    @DisplayName("Redis outage on anon path → fail-open (count without dedupe)")
    void anonRedisOutage_failOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(tracker.shouldCount(postId, null, "192.168.1.1"))
                .as("losing the dedupe is preferable to losing the view")
                .isTrue();
    }

    @Test
    @DisplayName("backwards-compat overload (no userId): uses Redis path")
    void backwardsCompatOverload_treatsAsAnonymous() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        assertThat(tracker.shouldCount(postId, "ip-1")).isTrue();
        verify(postViewRepository, never()).tryRecord(any(), any());
    }
}
