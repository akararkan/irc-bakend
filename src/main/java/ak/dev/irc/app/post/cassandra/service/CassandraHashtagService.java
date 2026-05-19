package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.MentionByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.PostByHashtagEntity;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.repository.HashtagCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.MentionByUserRepository;
import ak.dev.irc.app.post.cassandra.repository.PostByHashtagRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private static final Pattern MENTION = Pattern.compile("@(\\w+)");

    private final PostByHashtagRepository  postByHashtagRepo;
    private final HashtagCounterRepository hashtagCounterRepo;
    private final MentionByUserRepository  mentionByUserRepo;
    private final UserRepository           userRepo;
    private final CounterService           counterService;
    private final CassandraNotificationService notificationService;

    public record ExtractedEntities(List<String> hashtags, List<UUID> mentionedUserIds) {}

    /**
     * Extract hashtags + mentions from post text, persist the feed rows and
     * bump counters. Idempotent at the per-tag level (re-extracting the same
     * post would double-count, so do this exactly once per create).
     */
    public ExtractedEntities indexEntitiesForPost(UUID postId, UUID authorId,
                                                  String textContent, Instant createdAt,
                                                  String mediaUrl) {
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

        // Mentions — resolve @username → UUID via Postgres UserRepository
        Set<String> usernames = extractLower(MENTION, textContent);
        List<UUID> mentionedIds = new ArrayList<>();
        String authorLabel = userRepo.findById(authorId)
                .map(User::getUsername).map(u -> "@" + u).orElse("Someone");
        for (String username : usernames) {
            try {
                userRepo.findByUsername(username).ifPresent(user -> {
                    mentionByUserRepo.save(MentionByUserEntity.builder()
                            .mentionedUserId(user.getId())
                            .createdAt(createdAt)
                            .postId(postId)
                            .authorId(authorId)
                            .textPreview(preview)
                            .build());
                    mentionedIds.add(user.getId());

                    // Fire USER_MENTIONED notification per mentioned user.
                    notificationService.deliverAsync(
                            new CassandraNotificationService.DeliverRequest(
                                    user.getId(),
                                    NotificationKind.USER_MENTIONED,
                                    "You were mentioned",
                                    authorLabel + " mentioned you: \""
                                            + (preview == null ? "" : preview) + "\"",
                                    authorId,
                                    "Post", postId,
                                    "USER_MENTIONED:" + postId + ":" + user.getId()
                            ));
                });
            } catch (Exception e) {
                log.warn("[MENTION] resolve @{} failed: {}", username, e.getMessage());
            }
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
