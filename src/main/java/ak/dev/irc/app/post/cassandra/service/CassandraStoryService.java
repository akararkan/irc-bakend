package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryViewEntity;
import ak.dev.irc.app.post.cassandra.repository.StoryByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryViewRepository;
import ak.dev.irc.app.post.enums.StoryLifetime;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Service;

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

    private final StoryByAuthorRepository storyRepo;
    private final StoryLookupRepository   storyLookupRepo;
    private final StoryViewRepository     storyViewRepo;
    private final CloseFriendsService     closeFriendsService;
    private final UserFollowRepository    userFollowRepo;
    /** Channel stories: the "author" is a CHANNEL conversation — membership
     *  checks resolve against the chat domain (same cross-domain bridging as
     *  ChatNotificationService → CassandraNotificationService, reversed). */
    private final ak.dev.irc.app.chat.repository.ConversationRepository chatConversationRepo;
    private final ak.dev.irc.app.chat.repository.ConversationMemberRepository chatMemberRepo;
    private final CassandraStoryPollService pollService;
    private final ak.dev.irc.app.post.realtime.StoryTrayRealtimePublisher trayPublisher;
    private final ak.dev.irc.app.admin.moderation.PlatformKeywordService platformKeywords;
    /**
     * Used to write with explicit per-row {@code USING TTL <seconds>}, so the
     * 8 / 16 / 24-hour story windows actually expire at the row level — the
     * Spring-Data repository {@code save()} writes use the table's
     * {@code default_time_to_live} (24h), which can't be varied per-row.
     */
    private final CassandraOperations     cassandraOps;

    // ── Create / delete ─────────────────────────────────────────────────────

    /**
     * Legacy entry point — no lifetime supplied, defaults to {@link StoryLifetime#DEFAULT}
     * (24h). Kept so existing callers don't have to change at once.
     */
    public StoryByAuthorEntity createStory(UUID authorId, String storyType,
                                           String visibility, String mediaUrl,
                                           String thumbnailUrl, String textContent) {
        return createStory(authorId, storyType, visibility, mediaUrl, thumbnailUrl,
                textContent, StoryLifetime.DEFAULT);
    }

    /**
     * Create a story that auto-deletes after {@code lifetime} (8h / 16h / 24h).
     *
     * <p>The TTL is applied per-row via {@code INSERT … USING TTL} on both
     * {@code stories_by_author} (the author-partition row the tray reads) and
     * {@code story_lookup} (the point-read lookup). After {@code lifetime}
     * Cassandra tombstones both rows and they disappear from every read
     * surface — no expiry job, no orphan rows, no read-time filter.</p>
     *
     * <p>{@code expiresAt} on the row is kept in sync with the chosen TTL so
     * the UI (story tray ring, "expires in N hours" badge) renders correctly
     * without re-deriving from the table TTL.</p>
     */
    public StoryByAuthorEntity createStory(UUID authorId, String storyType,
                                           String visibility, String mediaUrl,
                                           String thumbnailUrl, String textContent,
                                           StoryLifetime lifetime) {
        if (lifetime == null) lifetime = StoryLifetime.DEFAULT;
        String flaggedKeyword = platformKeywords.blockOrFlag(textContent);
        UUID    storyId = UUID.randomUUID();
        Instant now     = Instant.now();
        Instant expires = now.plus(lifetime.duration());
        if (flaggedKeyword != null) {
            platformKeywords.recordHit("STORY", storyId.toString(), authorId, flaggedKeyword);
        }

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

        // Per-row TTL: overrides the table's default_time_to_live for this
        // insert only, so 8h / 16h stories actually disappear at their
        // chosen window. The same TTL is applied to the lookup row so the
        // point-read view dies with the author-partition row.
        InsertOptions ttlOpts = InsertOptions.builder()
                .ttl(lifetime.ttlSeconds())
                .build();
        cassandraOps.insert(row, ttlOpts);
        cassandraOps.insert(StoryLookupEntity.builder()
                .storyId(storyId).authorId(authorId)
                .createdAt(now).visibility(visibility)
                .expiresAt(expires)
                .build(), ttlOpts);

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
        log.info("[STORY] delete requested storyId={} actor={}", storyId, actorId);

        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) {
            // PREVIOUS bug: this branch returned silently → controller wrapped
            // the silent return in 204 NO_CONTENT → frontend interpreted it
            // as "delete succeeded" even though nothing was tombstoned.
            // Now we surface a 404 so the client can distinguish "already
            // gone / never existed" from "deleted just now". This is hit in
            // three real situations:
            //   1. The story_lookup row's TTL has fired but stories_by_author
            //      hasn't been tombstoned yet (per-row TTL, not atomic across tables).
            //   2. Re-issued delete on a story that was already removed.
            //   3. The story_lookup row was never written (e.g. an existing
            //      keyspace missing the `expires_at` column rejects the insert).
            log.warn("[STORY] delete miss — no story_lookup row for storyId={} actor={}",
                    storyId, actorId);
            throw new ResourceNotFoundException("Story", "id", storyId);
        }
        if (!meta.getAuthorId().equals(actorId)) {
            log.warn("[STORY] delete forbidden — storyId={} owned by {} but actor is {}",
                    storyId, meta.getAuthorId(), actorId);
            throw new SecurityException("Only the author can delete this story");
        }

        UUID   authorId   = meta.getAuthorId();
        String visibility = meta.getVisibility();

        // Core delete — must happen first so the HTTP path completes quickly
        // even if downstream cleanup hiccups.
        storyRepo.delete(authorId, meta.getCreatedAt(), storyId);
        storyLookupRepo.deleteById(storyId);
        log.info("[STORY] tombstoned stories_by_author + story_lookup for storyId={}", storyId);

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
        // Channel story: authorId is the channel's conversation id. Public
        // channel → visible to anyone; private → active subscribers only.
        if ("CHANNEL".equals(v)) {
            var channel = chatConversationRepo.findById(story.getAuthorId())
                    .filter(c -> c.getDeletedAt() == null && c.isChannel())
                    .orElse(null);
            if (channel == null) return false;
            if (channel.isPublicChannel()) return true;
            if (viewerId == null) return false;
            return chatMemberRepo.findMember(channel.getId(), viewerId)
                    .filter(ak.dev.irc.app.chat.entity.ConversationMember::canRead)
                    .isPresent();
        }
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
     *  before recording — silent reject if not allowed.
     *
     *  <p>The view row is TTL'd to the parent story's remaining lifetime,
     *  so an 8h story's view log dies with the story instead of lingering
     *  for the table-default 24h. Old lookup rows (no {@code expiresAt})
     *  fall back to {@link StoryLifetime#DEFAULT}.</p>
     */
    public void recordView(UUID storyId, UUID viewerId) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) return;
        if (meta.getAuthorId().equals(viewerId)) return;   // don't log self-views

        StoryByAuthorEntity story = new StoryByAuthorEntity();
        story.setAuthorId(meta.getAuthorId());
        story.setVisibility(meta.getVisibility());
        if (!canView(story, viewerId)) return;

        Instant now = Instant.now();
        int ttl = remainingTtlSeconds(meta.getExpiresAt(), now);
        cassandraOps.insert(StoryViewEntity.builder()
                .storyId(storyId)
                .viewedAt(now)
                .viewerId(viewerId)
                .build(), InsertOptions.builder().ttl(ttl).build());
    }

    /**
     * Seconds left until {@code expiresAt}, clamped to at least 1 (Cassandra
     * rejects TTL=0 with "TTL must be greater than 0"). Returns the default
     * 24h lifetime when {@code expiresAt} is null — old story_lookup rows
     * pre-date the {@code expires_at} column.
     */
    private static int remainingTtlSeconds(Instant expiresAt, Instant now) {
        if (expiresAt == null) return StoryLifetime.DEFAULT.ttlSeconds();
        long secs = expiresAt.getEpochSecond() - now.getEpochSecond();
        if (secs <= 0L) return 1;
        if (secs > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) secs;
    }

    /** Exposed for callers in the same package (e.g. {@link CassandraStoryPollService}). */
    int remainingTtlForStory(Instant expiresAt) {
        return remainingTtlSeconds(expiresAt, Instant.now());
    }

    /**
     * Viewer log — AUTHOR ONLY. Who watched your story is private to you
     * (Instagram semantics); without this check any caller could pull any
     * story's viewer list — a privacy leak and social-graph scraping vector.
     */
    public List<StoryViewEntity> viewersFor(UUID storyId, UUID requesterId, int pageSize) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) return List.of();
        // Channel story viewer log — visible to the channel's owner/admins.
        if ("CHANNEL".equals(meta.getVisibility())) {
            boolean staff = requesterId != null && chatMemberRepo
                    .findMember(meta.getAuthorId(), requesterId)
                    .filter(m -> m.isActive() && m.isAdminOrOwner())
                    .isPresent();
            if (!staff) throw new SecurityException("Only channel admins can view the viewer log");
            return storyViewRepo.recent(storyId, pageSize);
        }
        if (requesterId == null || !requesterId.equals(meta.getAuthorId())) {
            throw new SecurityException("Only the story author can view the viewer log");
        }
        return storyViewRepo.recent(storyId, pageSize);
    }
}
