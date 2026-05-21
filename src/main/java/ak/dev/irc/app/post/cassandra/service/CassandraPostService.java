package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.repository.PostByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.ReelsByDayRepository;
import ak.dev.irc.app.post.search.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Cassandra-backed post write path. The hot insert path is:
 *
 *   1.  posts_by_id              ← canonical
 *   2.  posts_by_author          ← profile feed
 *   3.  reels_by_day (REEL only) ← global reels discover feed
 *   4.  Trigger asynchronous fanout to feed_by_user for every follower
 *   5.  Index in Elasticsearch (best effort; not in the critical path)
 *
 * Steps 1-3 are synchronous so the create endpoint can return a complete
 * post. Steps 4 & 5 are async so a creator with 1M followers isn't blocked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraPostService {

    private final PostByIdRepository       postByIdRepo;
    private final PostByAuthorRepository   postByAuthorRepo;
    private final ReelsByDayRepository     reelsByDayRepo;
    private final FeedTimelineService      feedTimelineService;
    private final PostSearchService        postSearchService;
    private final CassandraSoundService    soundService;
    private final CassandraHashtagService  hashtagService;
    private final UserActivityService      userActivityService;

    /**
     * Create a post and persist it across every denormalized table that needs
     * a copy. The returned entity is the canonical posts_by_id row.
     */
    public PostByIdEntity createPost(CreatePostCommand cmd) {
        UUID    id        = UUID.randomUUID();
        Instant now       = Instant.now();
        String  preview   = preview(cmd.textContent());
        String  coverMedia = cmd.mediaUrls() == null || cmd.mediaUrls().isEmpty()
                              ? null : cmd.mediaUrls().get(0);

        // 1. canonical record
        PostByIdEntity canonical = PostByIdEntity.builder()
                .id(id)
                .authorId(cmd.authorId())
                .postType(cmd.postType())
                .status("PUBLISHED")
                .visibility(cmd.visibility())
                .textContent(cmd.textContent())
                .audioTrackUrl(cmd.audioTrackUrl())
                .audioTrackName(cmd.audioTrackName())
                .locationName(cmd.locationName())
                .locationLat(cmd.locationLat())
                .locationLng(cmd.locationLng())
                .sharedPostId(cmd.sharedPostId())
                .shareLink(cmd.shareLink())
                .mediaUrls(cmd.mediaUrls())
                .mediaTypes(cmd.mediaTypes())
                .createdAt(now)
                .updatedAt(now)
                .build();
        postByIdRepo.save(canonical);

        // 2. profile feed entry
        postByAuthorRepo.save(PostByAuthorEntity.builder()
                .authorId(cmd.authorId())
                .createdAt(now)
                .postId(id)
                .postType(cmd.postType())
                .visibility(cmd.visibility())
                .textPreview(preview)
                .mediaUrl(coverMedia)
                .build());

        // 3. reel global feed
        if ("REEL".equals(cmd.postType())) {
            reelsByDayRepo.save(ReelsByDayEntity.builder()
                    .dayBucket(LocalDate.now(ZoneOffset.UTC).toString())
                    .createdAt(now)
                    .postId(id)
                    .authorId(cmd.authorId())
                    .textPreview(preview)
                    .mediaUrl(coverMedia)
                    .build());
        }

        // 4. async fanout — never blocks the response
        feedTimelineService.fanoutAsync(id, cmd.authorId(), now, cmd.postType(),
                                       preview, coverMedia, cmd.visibility());

        // 5. async indexing — search remains eventually consistent
        postSearchService.indexAsync(canonical);

        // 6. sound adoption: index this post under the sound + bump use_count.
        //    Done synchronously so trending counts stay accurate; cheap (two writes).
        if (cmd.soundId() != null) {
            soundService.recordPostUsage(cmd.soundId(), id, cmd.authorId(), now);
        }

        // 7. hashtag + mention extraction. Done synchronously so the per-tag
        //    feed sees the post immediately and mentioned users can find it
        //    in their /mentions inbox before realtime delivery completes.
        hashtagService.indexEntitiesForPost(id, cmd.authorId(),
                                            cmd.textContent(), now, coverMedia);

        // 8. record on the author's activity feed.
        try {
            userActivityService.recordPostCreated(cmd.authorId(), id, cmd.postType());
        } catch (Exception e) {
            log.debug("[POST] activity record skipped: {}", e.getMessage());
        }

        return canonical;
    }

    private static String preview(String text) {
        if (text == null) return null;
        return text.length() > 280 ? text.substring(0, 280) : text;
    }

    public PostByIdEntity getById(UUID postId) {
        return postByIdRepo.findById(postId).orElse(null);
    }

    /**
     * Author-only partial update. Each non-null field on {@link EditPostCommand}
     * overwrites the corresponding column on {@code posts_by_id}. The fast
     * mirror on {@code posts_by_author} is updated best-effort so the profile
     * feed reflects the edit on next read; failures there are logged but do
     * not fail the request.
     *
     * @return the updated row, or {@code null} if the post did not exist
     * @throws SecurityException when {@code actorId} is not the post's author
     */
    public PostByIdEntity editPost(UUID postId, UUID actorId, EditPostCommand cmd) {
        PostByIdEntity existing = postByIdRepo.findById(postId).orElse(null);
        if (existing == null) return null;
        if (!actorId.equals(existing.getAuthorId())) {
            throw new SecurityException("Not the author");
        }

        boolean changed = false;
        if (cmd.textContent() != null) { existing.setTextContent(cmd.textContent()); changed = true; }
        if (cmd.visibility()  != null) { existing.setVisibility(cmd.visibility());   changed = true; }
        if (cmd.mediaUrls()   != null) { existing.setMediaUrls(cmd.mediaUrls());     changed = true; }
        if (cmd.mediaTypes()  != null) { existing.setMediaTypes(cmd.mediaTypes());   changed = true; }
        if (cmd.audioTrackUrl()  != null) { existing.setAudioTrackUrl(cmd.audioTrackUrl());   changed = true; }
        if (cmd.audioTrackName() != null) { existing.setAudioTrackName(cmd.audioTrackName()); changed = true; }
        if (cmd.locationName()   != null) { existing.setLocationName(cmd.locationName());     changed = true; }
        if (cmd.locationLat()    != null) { existing.setLocationLat(cmd.locationLat());       changed = true; }
        if (cmd.locationLng()    != null) { existing.setLocationLng(cmd.locationLng());       changed = true; }

        if (!changed) return existing;

        existing.setUpdatedAt(Instant.now());
        postByIdRepo.save(existing);

        // Best-effort mirror onto the profile-feed denorm row. We don't have
        // a primary-key-aware updateRow on posts_by_author — re-save with the
        // (author, created_at, post) tuple intact.
        try {
            postByAuthorRepo.save(PostByAuthorEntity.builder()
                    .authorId(existing.getAuthorId())
                    .createdAt(existing.getCreatedAt())
                    .postId(postId)
                    .postType(existing.getPostType())
                    .visibility(existing.getVisibility())
                    .textPreview(preview(existing.getTextContent()))
                    .mediaUrl(existing.getMediaUrls() == null || existing.getMediaUrls().isEmpty()
                              ? null : existing.getMediaUrls().get(0))
                    .build());
        } catch (Exception e) {
            log.warn("[POST] profile-feed mirror update failed for {}: {}", postId, e.getMessage());
        }

        // Best-effort search re-index — async.
        postSearchService.indexAsync(existing);

        log.info("[POST] edited {} (author={})", postId, actorId);
        return existing;
    }

    /**
     * Hard-delete a post and every denormalized copy. Caller must check
     * ownership (only the author can delete their own post — the controller
     * enforces this via the JWT principal). Returns {@code true} if the
     * post existed and was removed.
     *
     * <p>Cleans:
     * <ul>
     *   <li>{@code posts_by_id} — canonical row</li>
     *   <li>{@code posts_by_author} — profile feed entry</li>
     *   <li>{@code post_search} (Elasticsearch) — async best-effort</li>
     * </ul>
     * {@code feed_by_user} rows for followers and {@code reels_by_day} entries
     * expire naturally via TTL; we don't sweep them because Cassandra wide-row
     * deletions across N partitions would be expensive.</p>
     */
    public boolean deletePost(UUID postId) {
        PostByIdEntity canonical = postByIdRepo.findById(postId).orElse(null);
        if (canonical == null) return false;

        postByIdRepo.deleteById(postId);

        if (canonical.getAuthorId() != null && canonical.getCreatedAt() != null) {
            try {
                postByAuthorRepo.deleteRow(canonical.getAuthorId(),
                                           canonical.getCreatedAt(),
                                           postId);
            } catch (Exception e) {
                log.warn("[POST] profile-feed cleanup failed for {}: {}", postId, e.getMessage());
            }
        }

        postSearchService.deleteAsync(postId);
        log.info("[POST] deleted {} (author={})", postId, canonical.getAuthorId());
        return true;
    }

    public List<PostByAuthorEntity> profileFeed(UUID authorId, int pageSize) {
        return postByAuthorRepo.firstPage(authorId, pageSize);
    }

    public List<PostByAuthorEntity> profileFeedAfter(UUID authorId, Instant cursor, int pageSize) {
        return postByAuthorRepo.nextPage(authorId, cursor, pageSize);
    }

    public List<ReelsByDayEntity> reelsForDay(String day, int pageSize) {
        return reelsByDayRepo.firstPage(day, pageSize);
    }

    public record CreatePostCommand(
            UUID authorId,
            String postType,
            String visibility,
            String textContent,
            String audioTrackUrl,
            String audioTrackName,
            String locationName,
            Double locationLat,
            Double locationLng,
            UUID   sharedPostId,
            String shareLink,
            List<String> mediaUrls,
            List<String> mediaTypes,
            /** Optional — when set, this post is registered as a use of the
             *  sound library entry, incrementing its use_count. */
            UUID   soundId
    ) {}

    /**
     * Partial-update payload for {@link #editPost}. Every field is nullable;
     * a {@code null} field is left untouched on the existing post.
     */
    public record EditPostCommand(
            String textContent,
            String visibility,
            String audioTrackUrl,
            String audioTrackName,
            String locationName,
            Double locationLat,
            Double locationLng,
            List<String> mediaUrls,
            List<String> mediaTypes
    ) {}
}
