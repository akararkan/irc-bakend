package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.messages.PostMessages;
import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import ak.dev.irc.app.post.cassandra.entity.SaveLookupEntity;
import ak.dev.irc.app.post.cassandra.repository.CommentByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentReactionRepository;
import ak.dev.irc.app.post.cassandra.repository.MediaByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ReactionByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.ReelsByDayRepository;
import ak.dev.irc.app.post.cassandra.repository.ReplyByCommentRepository;
import ak.dev.irc.app.post.cassandra.repository.SaveByUserRepository;
import ak.dev.irc.app.post.cassandra.repository.SaveLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.ShareByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.ViewByPostRepository;
import ak.dev.irc.app.post.search.service.PostSearchService;
import ak.dev.irc.app.user.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    private final ak.dev.irc.app.admin.moderation.PlatformKeywordService platformKeywords;
    /** Bounded shared pool (AsyncConfig) — runs the post-delete cascade off the request thread. */
    private final ThreadPoolTaskExecutor   taskExecutor;
    private final ReelsByDayRepository     reelsByDayRepo;
    private final FeedTimelineService      feedTimelineService;
    private final PostSearchService        postSearchService;
    private final CassandraSoundService    soundService;
    private final CassandraHashtagService  hashtagService;
    private final UserActivityService      userActivityService;

    // Cascade-delete dependencies. Optional via @Autowired(required=false)
    // semantics is not used here — Spring will fail fast if any of these isn't
    // wired, which is exactly what we want: a post delete that silently skips
    // half its cleanup because a bean is missing would be worse than a startup
    // error.
    private final CommentByPostRepository    commentByPostRepo;
    private final CommentReactionRepository  commentReactionRepo;
    private final CommentCounterRepository   commentCounterRepo;
    private final ReplyByCommentRepository   replyByCommentRepo;
    private final ReactionByPostRepository   reactionByPostRepo;
    private final SaveLookupRepository       saveLookupRepo;
    private final SaveByUserRepository       saveByUserRepo;
    private final ViewByPostRepository       viewByPostRepo;
    private final ShareByPostRepository      shareByPostRepo;
    private final MediaByPostRepository      mediaByPostRepo;
    private final PostCounterRepository      postCounterRepo;
    private final NotificationRepository     notificationRepo;

    /**
     * Create a post and persist it across every denormalized table that needs
     * a copy. The returned entity is the canonical posts_by_id row.
     */
    public PostByIdEntity createPost(CreatePostCommand cmd) {
        // Platform keyword blocklist: BLOCK severity rejects here; FLAG lets
        // the post publish and drops a moderation-queue hit below.
        String flaggedKeyword = platformKeywords.blockOrFlag(cmd.textContent());

        UUID    id        = UUID.randomUUID();
        Instant now       = Instant.now();
        String  preview   = preview(cmd.textContent());
        if (flaggedKeyword != null) {
            platformKeywords.recordHit("POST", id.toString(), cmd.authorId(), flaggedKeyword);
        }
        // For reels, prefer the VIDEO-typed URL so feed rows always point to playable media.
        // For other post types, the first URL (typically the cover image) is correct.
        String  coverMedia = "REEL".equals(cmd.postType())
                              ? firstVideoUrl(cmd.mediaUrls(), cmd.mediaTypes())
                              : (cmd.mediaUrls() == null || cmd.mediaUrls().isEmpty() ? null : cmd.mediaUrls().get(0));

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
        //    The postType is threaded through so the unified content_by_tag
        //    fan-out picks ContentType.REEL vs POST correctly.
        hashtagService.indexEntitiesForPost(id, cmd.authorId(), cmd.postType(),
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

    private static String firstVideoUrl(List<String> urls, List<String> types) {
        if (urls == null || types == null) return urls == null || urls.isEmpty() ? null : urls.get(0);
        for (int i = 0; i < Math.min(urls.size(), types.size()); i++) {
            if ("VIDEO".equalsIgnoreCase(types.get(i))) return urls.get(i);
        }
        return urls.isEmpty() ? null : urls.get(0);
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
            throw new SecurityException(PostMessages.NOT_AUTHOR_MSG);
        }

        boolean changed = false;
        String previousText = existing.getTextContent();
        boolean textChanged = false;
        // Track which fields actually changed so the log line tells us at a glance
        // whether the client really sent textContent (vs sending the wrong key
        // and only updating visibility / media — the bug the FE hit).
        java.util.List<String> changedFields = new java.util.ArrayList<>(4);
        if (cmd.textContent() != null
                && !cmd.textContent().equals(existing.getTextContent())) {
            existing.setTextContent(cmd.textContent());
            changed = true;
            textChanged = true;
            changedFields.add("textContent");
        }
        if (cmd.visibility()  != null && !cmd.visibility().equals(existing.getVisibility())) {
            existing.setVisibility(cmd.visibility());   changed = true; changedFields.add("visibility");
        }
        if (cmd.mediaUrls()   != null) {
            existing.setMediaUrls(cmd.mediaUrls());     changed = true; changedFields.add("mediaUrls");
        }
        if (cmd.mediaTypes()  != null) {
            existing.setMediaTypes(cmd.mediaTypes());   changed = true; changedFields.add("mediaTypes");
        }
        if (cmd.audioTrackUrl()  != null) { existing.setAudioTrackUrl(cmd.audioTrackUrl());   changed = true; changedFields.add("audioTrackUrl"); }
        if (cmd.audioTrackName() != null) { existing.setAudioTrackName(cmd.audioTrackName()); changed = true; changedFields.add("audioTrackName"); }
        if (cmd.locationName()   != null) { existing.setLocationName(cmd.locationName());     changed = true; changedFields.add("locationName"); }
        if (cmd.locationLat()    != null) { existing.setLocationLat(cmd.locationLat());       changed = true; changedFields.add("locationLat"); }
        if (cmd.locationLng()    != null) { existing.setLocationLng(cmd.locationLng());       changed = true; changedFields.add("locationLng"); }

        if (!changed) {
            log.info("[POST] edit {} no-op (author={}) — request had no recognised fields", postId, actorId);
            return existing;
        }

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

        // Re-extract hashtags so a #foo→#bar edit doesn't leave the old tag
        // attached to the post in either the post-side hashtag feed or the
        // unified content_by_tag feed.
        if (textChanged) {
            String mediaUrl = existing.getMediaUrls() == null || existing.getMediaUrls().isEmpty()
                    ? null : existing.getMediaUrls().get(0);
            hashtagService.reindexEntitiesForPost(
                    postId, existing.getAuthorId(), existing.getPostType(),
                    previousText, existing.getTextContent(),
                    existing.getCreatedAt(), mediaUrl);
        }

        log.info("[POST] edited {} (author={}, fields={})", postId, actorId, changedFields);
        return existing;
    }

    /**
     * Hard-delete a post and every denormalized copy. Caller must check
     * ownership (only the author can delete their own post — the controller
     * enforces this via the JWT principal). Returns {@code true} if the
     * post existed and was removed.
     *
     * <p>Cascade order — each step is best-effort and logged on failure so a
     * single misbehaving table can't strand the rest of the cleanup:
     * <ol>
     *   <li>Per-comment fan-out: comment_reactions + comment_counters + replies
     *       (driven by a partition scan of comments_by_post)</li>
     *   <li>comments_by_post partition delete</li>
     *   <li>Per-save user-fan-out cleanup: drain saves_by_post_user to find
     *       the (user_id, created_at) tuples and wipe saves_by_user</li>
     *   <li>saves_by_post_user partition delete</li>
     *   <li>reactions_by_post / views_by_post / shares_by_post / media_by_post
     *       partition deletes</li>
     *   <li>post_counters row delete</li>
     *   <li>posts_by_author profile-feed row + posts_by_id canonical row</li>
     *   <li>Elasticsearch index entry, hashtag fan-out, Postgres notifications</li>
     * </ol>
     * The user-partitioned tables {@code reactions_by_user} and the per-author
     * feed_by_user rows aren't swept here — they're partitioned by user, so a
     * server-side cleanup would need to walk every user. They expire naturally
     * (feed via TTL; reactions_by_user is fine to leave a dangling row because
     * read paths join through posts_by_id and skip the missing parent).</p>
     */
    public boolean deletePost(UUID postId) {
        PostByIdEntity canonical = postByIdRepo.findById(postId).orElse(null);
        if (canonical == null) return false;

        // Make the post disappear IMMEDIATELY: canonical row, profile-feed
        // row, and the search index. Everything else (comment/save fan-outs,
        // partition sweeps) is O(comments + saves) driver round-trips — that
        // cascade runs on the async executor so a viral post can't pin the
        // DELETE request thread for seconds.
        if (canonical.getAuthorId() != null && canonical.getCreatedAt() != null) {
            try {
                postByAuthorRepo.deleteRow(canonical.getAuthorId(),
                                           canonical.getCreatedAt(),
                                           postId);
            } catch (Exception e) {
                log.warn("[POST] profile-feed cleanup failed for {}: {}", postId, e.getMessage());
            }
        }
        postByIdRepo.deleteById(postId);
        try { postSearchService.deleteAsync(postId); }
        catch (Exception e) { log.warn("[POST] ES delete failed for {}: {}", postId, e.getMessage()); }

        final PostByIdEntity snapshot = canonical;
        taskExecutor.execute(() -> cascadeDelete(postId, snapshot));

        log.info("[POST] hard-deleted {} (author={}); cascade running async",
                postId, canonical.getAuthorId());
        return true;
    }

    /**
     * Background sweep of every child table of a deleted post. Best-effort
     * per step — a failing sub-delete is logged and skipped, since read paths
     * join through posts_by_id (already gone) and tolerate orphans.
     */
    private void cascadeDelete(UUID postId, PostByIdEntity canonical) {
        // 1. Comments — walk the partition once so we can fan out to each
        //    comment's child tables before wiping the parent partition.
        try {
            List<CommentByPostEntity> comments = commentByPostRepo.findAllForPost(postId);
            for (CommentByPostEntity c : comments) {
                UUID commentId = c.getCommentId();
                if (commentId == null) continue;
                try { commentReactionRepo.deleteAllForComment(commentId); }
                catch (Exception e) { log.warn("[POST] comment reactions cleanup failed comment={}: {}", commentId, e.getMessage()); }
                try { replyByCommentRepo.deleteAllUnder(commentId); }
                catch (Exception e) { log.warn("[POST] replies cleanup failed comment={}: {}", commentId, e.getMessage()); }
                try { commentCounterRepo.deleteByCommentId(commentId); }
                catch (Exception e) { log.warn("[POST] comment counter cleanup failed comment={}: {}", commentId, e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("[POST] comment scan failed for {}: {}", postId, e.getMessage());
        }
        try { commentByPostRepo.deleteAllForPost(postId); }
        catch (Exception e) { log.warn("[POST] comments partition delete failed for {}: {}", postId, e.getMessage()); }

        // 2. Save fan-out — drain the per-post save list so we can clean the
        //    matching saves_by_user rows for everyone who saved this post.
        try {
            List<SaveLookupEntity> saves = saveLookupRepo.findAllByPostId(postId);
            for (SaveLookupEntity s : saves) {
                if (s.getUserId() == null || s.getCreatedAt() == null) continue;
                try { saveByUserRepo.delete(s.getUserId(), s.getCreatedAt(), postId); }
                catch (Exception e) { log.warn("[POST] saves_by_user cleanup failed user={}: {}", s.getUserId(), e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("[POST] save fan-out scan failed for {}: {}", postId, e.getMessage());
        }
        try { saveLookupRepo.deleteAllForPost(postId); }
        catch (Exception e) { log.warn("[POST] save-lookup partition delete failed for {}: {}", postId, e.getMessage()); }

        // 3. Reactions / views / shares / media — partition-level deletes.
        try { reactionByPostRepo.deleteAllForPost(postId); }
        catch (Exception e) { log.warn("[POST] reactions partition delete failed for {}: {}", postId, e.getMessage()); }
        try { viewByPostRepo.deleteAllForPost(postId); }
        catch (Exception e) { log.warn("[POST] views partition delete failed for {}: {}", postId, e.getMessage()); }
        try { shareByPostRepo.deleteAllForPost(postId); }
        catch (Exception e) { log.warn("[POST] shares partition delete failed for {}: {}", postId, e.getMessage()); }
        try { mediaByPostRepo.deleteAllFor(postId); }
        catch (Exception e) { log.warn("[POST] media partition delete failed for {}: {}", postId, e.getMessage()); }

        // 4. post_counters row.
        try { postCounterRepo.deleteByPostId(postId); }
        catch (Exception e) { log.warn("[POST] post counter delete failed for {}: {}", postId, e.getMessage()); }

        // 5. Hashtag fan-out + notifications. (Canonical row, profile-feed
        //    row and the ES entry were already removed synchronously.)
        try {
            hashtagService.clearEntitiesForPost(
                    postId, canonical.getTextContent(), canonical.getCreatedAt());
        } catch (Exception e) {
            log.warn("[POST] hashtag cleanup failed for {}: {}", postId, e.getMessage());
        }

        try { notificationRepo.deleteAllByResourceId(postId); }
        catch (Exception e) { log.warn("[POST] notification cleanup failed for {}: {}", postId, e.getMessage()); }

        log.info("[POST] cascade complete for {} (author={})", postId, canonical.getAuthorId());
    }

    public List<PostByAuthorEntity> profileFeed(UUID authorId, int pageSize) {
        return postByAuthorRepo.firstPage(authorId, pageSize);
    }

    public List<PostByAuthorEntity> profileFeedAfter(UUID authorId, Instant cursor, int pageSize) {
        return postByAuthorRepo.nextPage(authorId, cursor, pageSize);
    }

    /** A single author's reels, newest first, cursor-paginated (for the profile Reels tab). */
    public List<PostByAuthorEntity> reelsByAuthor(UUID authorId, int pageSize) {
        return postByAuthorRepo.reelsByAuthor(authorId, pageSize);
    }

    public List<PostByAuthorEntity> reelsByAuthorAfter(UUID authorId, Instant cursor, int pageSize) {
        return postByAuthorRepo.reelsByAuthorAfter(authorId, cursor, pageSize);
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
     *
     * <p>{@link #textContent} accepts the JSON keys {@code textContent},
     * {@code text}, {@code content}, or {@code body} — frontends differ on
     * naming and Jackson would otherwise silently drop a mismatch and leave
     * the post text unchanged. Same idea for {@link #mediaUrls} accepting
     * {@code media}/{@code images}.</p>
     */
    public record EditPostCommand(
            @com.fasterxml.jackson.annotation.JsonAlias({"text", "content", "body"})
            String textContent,
            String visibility,
            String audioTrackUrl,
            String audioTrackName,
            String locationName,
            Double locationLat,
            Double locationLng,
            @com.fasterxml.jackson.annotation.JsonAlias({"media", "images"})
            List<String> mediaUrls,
            List<String> mediaTypes
    ) {}
}
