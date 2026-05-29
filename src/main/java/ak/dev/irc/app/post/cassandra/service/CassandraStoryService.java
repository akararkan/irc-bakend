package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryViewEntity;
import ak.dev.irc.app.post.cassandra.repository.StoryByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryViewRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cassandra-backed stories.
 *
 * Schema design recap:
 *   • stories_by_author   ← author partition, clustered DESC by created_at,
 *                            with default_time_to_live = 86400 (24h auto-expire)
 *   • story_lookup        ← point-read by story_id (also TTL'd 24h)
 *   • story_views_by_story ← viewer log, TTL'd 24h
 *
 * Visibility resolution:
 *   PUBLIC          everyone (anon ok)
 *   FOLLOWERS_ONLY  viewer must follow author (Postgres UserFollow check)
 *   CLOSE_FRIENDS   viewer must be on author's close-friends list (Cassandra)
 *   ONLY_ME         viewer == author
 *
 * The viewer-list endpoints (who saw my story) deliberately stream the most
 * recent viewers — sorting in Cassandra at the partition level is free since
 * story_views_by_story is clustered DESC.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraStoryService {

    private static final Duration STORY_LIFETIME = Duration.ofHours(24);

    private final StoryByAuthorRepository storyRepo;
    private final StoryLookupRepository   storyLookupRepo;
    private final StoryViewRepository     storyViewRepo;
    private final CloseFriendsService     closeFriendsService;
    private final UserFollowRepository    userFollowRepo;
    private final CassandraStoryPollService pollService;
    private final ak.dev.irc.app.post.realtime.StoryTrayRealtimePublisher trayPublisher;

    // ── Create / delete ─────────────────────────────────────────────────────

    public StoryByAuthorEntity createStory(UUID authorId, String storyType,
                                           String visibility, String mediaUrl,
                                           String thumbnailUrl, String textContent) {
        UUID    storyId = UUID.randomUUID();
        Instant now     = Instant.now();
        Instant expires = now.plus(STORY_LIFETIME);

        StoryByAuthorEntity row = StoryByAuthorEntity.builder()
                .authorId(authorId)
                .createdAt(now)
                .storyId(storyId)
                .storyType(storyType)
                .visibility(visibility)
                .mediaUrl(mediaUrl)
                .thumbnailUrl(thumbnailUrl)
                .textContent(textContent)
                .expiresAt(expires)
                .build();
        storyRepo.save(row);

        storyLookupRepo.save(StoryLookupEntity.builder()
                .storyId(storyId).authorId(authorId)
                .createdAt(now).visibility(visibility)
                .build());

        return row;
    }

    /**
     * Author-only delete. The TTL would clean up in 24h anyway, but explicit
     * delete supports "I changed my mind" before then.
     *
     * <p>Cleans up:
     * <ul>
     *   <li>{@code stories_by_author} + {@code story_lookup} canonical rows.</li>
     *   <li>Attached poll (all 5 tables) via
     *       {@link CassandraStoryPollService#deletePollFor(UUID)}.</li>
     *   <li>Pushes {@code STORY_REMOVED} on the story-tray channel to every
     *       viewer whose tray ring this story lit (visibility-aware: PUBLIC
     *       / FOLLOWERS_ONLY → all followers; CLOSE_FRIENDS → close-friends
     *       list; ONLY_ME → just the author themselves) so the ring greys
     *       out without a refresh.</li>
     * </ul>
     * Story views are intentionally left to TTL (24h) — they're per-storyId
     * analytics, no cross-table reads, and proactive cleanup adds latency to
     * the HTTP response with no user-visible benefit.</p>
     *
     * <p>The cleanup phases are best-effort and isolated — a failure in one
     * doesn't block the others. The story-side delete itself is the only
     * non-negotiable step (and runs first).</p>
     */
    public void deleteStory(UUID storyId, UUID actorId) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) return;
        if (!meta.getAuthorId().equals(actorId)) {
            throw new SecurityException("Not the author");
        }

        UUID   authorId   = meta.getAuthorId();
        String visibility = meta.getVisibility();

        // Core delete — must happen first so the HTTP path completes quickly
        // even if downstream cleanup hiccups.
        storyRepo.delete(authorId, meta.getCreatedAt(), storyId);
        storyLookupRepo.deleteById(storyId);

        // Poll cleanup (no-op when the story had no poll).
        try {
            pollService.deletePollFor(storyId);
        } catch (Exception e) {
            log.warn("[STORY] poll cleanup for {} failed: {}", storyId, e.getMessage());
        }

        // Tray fan-out — STORY_REMOVED to every viewer whose ring was lit.
        try {
            broadcastStoryRemoved(authorId, storyId, visibility);
        } catch (Exception e) {
            log.warn("[STORY] tray fan-out for {} failed: {}", storyId, e.getMessage());
        }
    }

    /** Visibility-aware fan-out — same shape as the publish-time NEW_STORY
     *  push but with {@link ak.dev.irc.app.post.realtime.StoryTrayEventType#STORY_REMOVED}. */
    private void broadcastStoryRemoved(UUID authorId, UUID storyId, String visibility) {
        ak.dev.irc.app.post.realtime.StoryTrayEvent event =
                ak.dev.irc.app.post.realtime.StoryTrayEvent.builder()
                        .eventType(ak.dev.irc.app.post.realtime.StoryTrayEventType.STORY_REMOVED)
                        .authorId(authorId)
                        .storyId(storyId)
                        .build();

        // Always notify the author — their own tray reflects own stories.
        publishSafe(authorId, event);

        if (visibility == null || "ONLY_ME".equals(visibility)) return;

        if ("CLOSE_FRIENDS".equals(visibility)) {
            for (ak.dev.irc.app.post.cassandra.entity.CloseFriendEntity cf
                    : closeFriendsService.listFor(authorId)) {
                publishSafe(cf.getFriendId(), event);
            }
            return;
        }

        // PUBLIC, FOLLOWERS_ONLY → all followers, keyset-paged so a celebrity
        // delete doesn't OOM. Capped at 50k recipients (same ceiling as the
        // publish-side fan-out cited in the trending-digest job).
        final int pageSize = 500;
        final int totalCap = 50_000;
        int delivered = 0;
        UUID after    = null;
        while (delivered < totalCap) {
            List<UUID> batch = (after == null)
                    ? userFollowRepo.findFollowerIds(authorId,
                            org.springframework.data.domain.PageRequest.of(0, pageSize))
                    : userFollowRepo.findFollowerIdsAfter(authorId, after,
                            org.springframework.data.domain.PageRequest.of(0, pageSize));
            if (batch.isEmpty()) break;
            for (UUID followerId : batch) {
                publishSafe(followerId, event);
                delivered++;
            }
            after = batch.get(batch.size() - 1);
            if (batch.size() < pageSize) break;
        }
    }

    private void publishSafe(UUID viewerId,
                             ak.dev.irc.app.post.realtime.StoryTrayEvent event) {
        try { trayPublisher.publish(viewerId, event); }
        catch (Exception e) {
            log.debug("[STORY] tray publish to {} skipped: {}", viewerId, e.getMessage());
        }
    }

    // ── Read with visibility filter ─────────────────────────────────────────

    /**
     * Active stories for an author, filtered by what {@code viewerId} can see.
     * Pass {@code null} for anon viewers — they'll only see PUBLIC.
     */
    public List<StoryByAuthorEntity> activeStoriesFor(UUID authorId, UUID viewerId) {
        List<StoryByAuthorEntity> raw = storyRepo.activeStories(authorId);
        List<StoryByAuthorEntity> visible = new ArrayList<>(raw.size());
        for (StoryByAuthorEntity s : raw) {
            if (canView(s, viewerId)) visible.add(s);
        }
        return visible;
    }

    public boolean canView(StoryByAuthorEntity story, UUID viewerId) {
        String v = story.getVisibility();
        if (v == null || "PUBLIC".equals(v)) return true;
        if (viewerId == null) return false;
        if (viewerId.equals(story.getAuthorId())) return true;       // own story
        if ("ONLY_ME".equals(v)) return false;
        if ("FOLLOWERS_ONLY".equals(v)) {
            try {
                return userFollowRepo.isFollowing(viewerId, story.getAuthorId());
            } catch (Exception e) {
                log.debug("[STORY] follow check failed: {}", e.getMessage());
                return false;
            }
        }
        if ("CLOSE_FRIENDS".equals(v)) {
            return closeFriendsService.isCloseFriend(story.getAuthorId(), viewerId);
        }
        return false;
    }

    // ── Viewer log ──────────────────────────────────────────────────────────

    /** Record that {@code viewerId} watched a story. Visibility is enforced
     *  before recording — silent reject if not allowed. */
    public void recordView(UUID storyId, UUID viewerId) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) return;
        if (meta.getAuthorId().equals(viewerId)) return;   // don't log self-views

        StoryByAuthorEntity story = new StoryByAuthorEntity();
        story.setAuthorId(meta.getAuthorId());
        story.setVisibility(meta.getVisibility());
        if (!canView(story, viewerId)) return;

        storyViewRepo.save(StoryViewEntity.builder()
                .storyId(storyId)
                .viewedAt(Instant.now())
                .viewerId(viewerId)
                .build());
    }

    public List<StoryViewEntity> viewersFor(UUID storyId, int pageSize) {
        return storyViewRepo.recent(storyId, pageSize);
    }
}
