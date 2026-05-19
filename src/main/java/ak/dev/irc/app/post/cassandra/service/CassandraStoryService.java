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

    /** Author-only delete. The TTL would clean up in 24h anyway, but explicit
     *  delete supports "I changed my mind" before then. */
    public void deleteStory(UUID storyId, UUID actorId) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) return;
        if (!meta.getAuthorId().equals(actorId)) {
            throw new SecurityException("Not the author");
        }
        storyRepo.delete(meta.getAuthorId(), meta.getCreatedAt(), storyId);
        storyLookupRepo.deleteById(storyId);
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
