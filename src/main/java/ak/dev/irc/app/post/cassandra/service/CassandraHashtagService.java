package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.common.tag.ContentType;
import ak.dev.irc.app.common.tag.service.ContentTagService;
import ak.dev.irc.app.post.cassandra.entity.MentionByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByHashtagEntity;
import ak.dev.irc.app.post.cassandra.repository.HashtagCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.MentionByUserRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByHashtagRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hashtag and @-mention extraction from post text + per-tag / per-user feeds.
 *
 * Extraction patterns:
 *   • #word — alphanumeric + underscore, case-insensitive (stored lowercased)
 *   • @username — same charset; resolved against UserRepository to a UUID
 *
 * Write fan-out per post that includes #foo:
 *   • posts_by_hashtag      ← "all posts tagged #foo" feed
 *   • hashtag_counters++    ← post_count for trending
 *
 * Write fan-out per @mention:
 *   • mentions_by_user      ← "posts that mention me" inbox
 *
 * Notifications: this service only writes the structural rows. A separate
 * NotificationService is responsible for fanning a MENTION_NOTIFICATION
 * notification to the mentioned user — keeps responsibilities clean.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraHashtagService {

    private static final Pattern HASHTAG = Pattern.compile("#(\\w+)");

    private final PostByHashtagRepository  postByHashtagRepo;
    private final HashtagCounterRepository hashtagCounterRepo;
    private final MentionByUserRepository  mentionByUserRepo;
    private final UserRepository           userRepo;
    private final ak.dev.irc.app.user.repository.UserBlockRepository userBlockRepo;
    private final CounterService           counterService;
    private final CassandraNotificationService notificationService;
    private final ContentTagService        contentTagService;
    private final CqlOperations            cqlOperations;

    public record ExtractedEntities(List<String> hashtags, List<UUID> mentionedUserIds) {}

    /** Extract hashtag tokens from post text (lowercased, deduped). Useful when
     *  the caller needs the set without writing fan-out rows (edit diffing). */
    public static Set<String> extractHashtags(String text) {
        return extractLower(HASHTAG, text);
    }

    /**
     * Extract hashtags + mentions from post text, persist the feed rows and
     * bump counters. Idempotent at the per-tag level (re-extracting the same
     * post would double-count, so do this exactly once per create).
     *
     * <p>Mention resolution uses a single {@code WHERE username IN (?)} query
     * — a post with N @mentions costs ONE Postgres round-trip, not N
     * sequential ones. The author-label lookup needed for the notification
     * body is deferred onto the async executor via
     * {@link CassandraNotificationService#deliverAllAsync(java.util.function.Supplier)},
     * so neither it nor the mention notifications block the post-create
     * response.</p>
     */
    public ExtractedEntities indexEntitiesForPost(UUID postId, UUID authorId, String postType,
                                                  String textContent, Instant createdAt,
                                                  String mediaUrl) {
        return indexEntitiesForPost(postId, authorId, postType, textContent, createdAt, mediaUrl,
                Set.of());
    }

    /**
     * @param skipNotifyHandles handles (lowercase) that must NOT be re-notified —
     *                          the edit path passes the pre-edit mention set so
     *                          only newly added handles get pinged; mirror rows
     *                          are still (re)written for every current mention.
     */
    private ExtractedEntities indexEntitiesForPost(UUID postId, UUID authorId, String postType,
                                                   String textContent, Instant createdAt,
                                                   String mediaUrl, Set<String> skipNotifyHandles) {
        String preview = preview(textContent);

        // Hashtags
        Set<String> tags = extractLower(HASHTAG, textContent);
        for (String tag : tags) {
            try {
                postByHashtagRepo.save(PostByHashtagEntity.builder()
                        .hashtag(tag).createdAt(createdAt).postId(postId)
                        .authorId(authorId).textPreview(preview).mediaUrl(mediaUrl)
                        .build());
                counterService.incrementHashtagPostCount(tag);
            } catch (Exception e) {
                log.warn("[HASHTAG] index #{} failed: {}", tag, e.getMessage());
            }
        }

        // Unified tag fan-out so #hajj on a post shows up in /tags/hajj/content
        // alongside questions and research (#1 from the FE integration notes).
        // ContentTagService is itself best-effort — it never throws into our path.
        if (!tags.isEmpty()) {
            contentTagService.tag(
                    "REEL".equalsIgnoreCase(postType) ? ContentType.REEL : ContentType.POST,
                    postId, authorId, preview, createdAt, tags);
        }

        // Mentions — same grammar as every other surface (MentionExtractor:
        // dot/underscore charset, email-safe look-behind), then a single
        // batched Postgres query for all @usernames.
        Set<String> usernames = ak.dev.irc.app.common.util.MentionExtractor
                .extract(textContent).getUsernames();
        List<UUID> mentionedIds = new ArrayList<>();
        if (usernames.isEmpty()) {
            return new ExtractedEntities(new ArrayList<>(tags), mentionedIds);
        }

        List<User> resolved;
        try {
            resolved = userRepo.findAllByUsernameIn(usernames);
        } catch (Exception e) {
            log.warn("[MENTION] batched resolve failed: {}", e.getMessage());
            return new ExtractedEntities(new ArrayList<>(tags), mentionedIds);
        }

        // Self-mentions never count, and blocked pairs are excluded up front so
        // the mirror feed matches what the delivery engine would allow anyway.
        resolved = new ArrayList<>(resolved);
        resolved.removeIf(u -> u.getId().equals(authorId));
        if (!resolved.isEmpty()) {
            try {
                Set<UUID> ids = new java.util.HashSet<>();
                for (User u : resolved) ids.add(u.getId());
                Set<UUID> blocked = new java.util.HashSet<>(userBlockRepo.findBlockedAmong(authorId, ids));
                if (!blocked.isEmpty()) resolved.removeIf(u -> blocked.contains(u.getId()));
            } catch (Exception e) {
                log.debug("[MENTION] block filter skipped: {}", e.getMessage());
            }
        }

        // Write the mention-mirror rows synchronously (they're per-recipient
        // partition, fast). The notification + author-label lookup is async.
        Map<UUID, String> mentionedUsernames = new HashMap<>(resolved.size());
        for (User user : resolved) {
            try {
                mentionByUserRepo.save(MentionByUserEntity.builder()
                        .mentionedUserId(user.getId())
                        .createdAt(createdAt)
                        .postId(postId)
                        .authorId(authorId)
                        .textPreview(preview)
                        .build());
                mentionedIds.add(user.getId());
                mentionedUsernames.put(user.getId(), user.getUsername());
            } catch (Exception e) {
                log.warn("[MENTION] mirror @{} failed: {}", user.getUsername(), e.getMessage());
            }
        }

        // Notify only handles NOT carried over from the pre-edit body — an
        // edit re-pings the newly tagged, never everyone from the first save.
        Map<UUID, String> toNotify = new HashMap<>(mentionedUsernames);
        if (!skipNotifyHandles.isEmpty()) {
            toNotify.entrySet().removeIf(e ->
                    e.getValue() != null && skipNotifyHandles.contains(e.getValue().toLowerCase()));
        }

        // Build + deliver all USER_MENTIONED notifications in one async task.
        // The author-label read happens on the executor thread, not here.
        if (!toNotify.isEmpty()) {
            notificationService.deliverAllAsync(() -> {
                String authorLabel = userRepo.findById(authorId)
                        .map(User::getUsername).map(u -> "@" + u).orElse("Someone");
                List<CassandraNotificationService.DeliverRequest> batch =
                        new ArrayList<>(toNotify.size());
                for (UUID mentionedId : toNotify.keySet()) {
                    batch.add(new CassandraNotificationService.DeliverRequest(
                            mentionedId,
                            NotificationKind.USER_MENTIONED,
                            "You were mentioned",
                            authorLabel + " mentioned you: \""
                                    + (preview == null ? "" : preview) + "\"",
                            authorId,
                            "Post", postId,
                            "USER_MENTIONED:" + postId + ":" + mentionedId
                    ));
                }
                return batch;
            });
        }

        return new ExtractedEntities(new ArrayList<>(tags), mentionedIds);
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public List<PostByHashtagEntity> postsForTag(String tag, int pageSize) {
        return postByHashtagRepo.firstPage(tag.toLowerCase(), pageSize);
    }

    public List<PostByHashtagEntity> postsForTagAfter(String tag, Instant cursor, int pageSize) {
        return postByHashtagRepo.nextPage(tag.toLowerCase(), cursor, pageSize);
    }

    public List<MentionByUserEntity> mentionsForUser(UUID userId, int pageSize) {
        return mentionByUserRepo.firstPage(userId, pageSize);
    }

    public Long postCountFor(String tag) {
        return hashtagCounterRepo.findById(tag.toLowerCase())
                .map(c -> c.getPostCount()).orElse(0L);
    }

    // ── Edit / delete fan-out (clears denormalised rows) ─────────────────────

    /**
     * Remove every fan-out row this post wrote on create. Call from the post
     * delete path. Best-effort: a failure on one tag never blocks the others.
     *
     * <p>The old {@code posts_by_hashtag} rows are derived from the post's
     * current text — there's no reverse-index on that side, so the caller
     * supplies the text. The unified {@code content_by_tag} side uses its
     * own {@code tags_by_content} reverse-index and so doesn't need the text.</p>
     */
    public void clearEntitiesForPost(UUID postId, String oldTextContent, Instant createdAt) {
        Set<String> tags = extractLower(HASHTAG, oldTextContent);
        for (String tag : tags) {
            try {
                cqlOperations.execute(
                        "DELETE FROM posts_by_hashtag WHERE hashtag = ? AND created_at = ? AND post_id = ?",
                        tag, createdAt, postId);
                counterService.decrementHashtagPostCount(tag);
            } catch (Exception e) {
                log.warn("[HASHTAG] clear #{} for {} failed: {}", tag, postId, e.getMessage());
            }
        }
        // Mention mirror rows for handles in the old text — one batched
        // resolve, then per-recipient point deletes (removing a mention from
        // the text removes the post from that user's mentions-of-me feed).
        Set<String> oldHandles = ak.dev.irc.app.common.util.MentionExtractor
                .extract(oldTextContent).getUsernames();
        if (!oldHandles.isEmpty()) {
            try {
                for (User u : userRepo.findAllByUsernameIn(oldHandles)) {
                    cqlOperations.execute(
                            "DELETE FROM mentions_by_user WHERE mentioned_user_id = ? AND created_at = ? AND post_id = ?",
                            u.getId(), createdAt, postId);
                }
            } catch (Exception e) {
                log.warn("[MENTION] mirror clear for {} failed: {}", postId, e.getMessage());
            }
        }

        // Wipe the unified content_by_tag entries even if the post had no #s
        // (cheap empty call; covers schema drift where the post side missed an
        // index).
        contentTagService.untag(postId);
    }

    /**
     * Re-extract hashtags + mentions after a post edit. Called from
     * {@code editPost} only when the text actually changed. The strategy is
     * "clear-all + re-index" for the structural rows — but notifications are
     * <b>delta-only</b>: handles already present in the old body are passed as
     * skip-list so an edit never re-pings everyone tagged the first time.
     */
    public ExtractedEntities reindexEntitiesForPost(UUID postId, UUID authorId, String postType,
                                                    String oldTextContent, String newTextContent,
                                                    Instant createdAt, String mediaUrl) {
        clearEntitiesForPost(postId, oldTextContent, createdAt);
        Set<String> previouslyMentioned = ak.dev.irc.app.common.util.MentionExtractor
                .extract(oldTextContent).getUsernames();
        return indexEntitiesForPost(postId, authorId, postType, newTextContent, createdAt, mediaUrl,
                previouslyMentioned);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Set<String> extractLower(Pattern p, String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(1).toLowerCase());
        return out;
    }

    private static String preview(String text) {
        if (text == null) return null;
        return text.length() > 280 ? text.substring(0, 280) : text;
    }
}
