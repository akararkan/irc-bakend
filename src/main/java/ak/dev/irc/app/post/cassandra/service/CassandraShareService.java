package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.entity.ShareByPostEntity;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.ShareByPostRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.share.FrontendUrlResolver;
import ak.dev.irc.app.share.ShareLinkInfo;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Share / repost ledger for posts.
 *
 * Distinct from a "repost" (which creates a new Post row with shared_post_id
 * set — that path lives in {@link CassandraPostService}). This service tracks
 * the platform-level "share" event — when a user taps Share and sends the
 * post to another surface (DM, external app, etc.) with an optional caption.
 *
 * Append-only — there is no "unshare". To remove a share, the user deletes
 * the share row directly (not implemented yet; not a common UX).
 *
 * Counter bump fires on every share so the post-detail UI can show
 * "shared N times".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraShareService {

    private final ShareByPostRepository shareRepo;
    private final PostCounterRepository postCounterRepo;
    private final CounterService        counterService;
    private final PostRealtimePublisher realtimePublisher;
    private final PostByIdRepository    postRepo;
    private final UserRepository        userRepo;
    private final CassandraNotificationService notificationService;
    private final FrontendUrlResolver   frontendUrlResolver;
    private final AuthorAffinityService affinityService;

    public ShareByPostEntity recordShare(UUID postId, UUID sharerId, String caption) {
        UUID    shareId = UUID.randomUUID();
        Instant now     = Instant.now();

        ShareByPostEntity row = ShareByPostEntity.builder()
                .postId(postId).createdAt(now).shareId(shareId)
                .sharerId(sharerId).caption(caption)
                .build();
        shareRepo.save(row);

        counterService.incrementPostShares(postId);
        broadcast(postId, sharerId);
        notifyShare(postId, sharerId);
        affinityService.recordForPost(sharerId, postId, AuthorAffinityService.WEIGHT_SHARE);
        return row;
    }

    private void notifyShare(UUID postId, UUID actorId) {
        try {
            PostByIdEntity post = postRepo.findById(postId).orElse(null);
            if (post == null || post.getAuthorId() == null) return;
            String actor = userRepo.findById(actorId)
                    .map(User::getUsername).map(u -> "@" + u).orElse("Someone");
            notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                    post.getAuthorId(),
                    NotificationKind.POST_SHARED,
                    "Your post was shared",
                    actor + " shared your post",
                    actorId,
                    "Post", postId,
                    "POST_SHARED:" + postId
            ));
        } catch (Exception e) {
            log.debug("[SHARE] notify skipped: {}", e.getMessage());
        }
    }

    public List<ShareByPostEntity> recentShares(UUID postId, int pageSize) {
        return shareRepo.recent(postId, pageSize);
    }

    // ── Unified share-link API (parity with research / Q&A) ──────────────────

    /**
     * Returns the unified {@link ShareLinkInfo} without bumping the counter —
     * for the inline share UI before the user actually copies the link.
     */
    public ShareLinkInfo previewShareLink(UUID postId, String baseUrl) {
        PostByIdEntity post = postRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));
        long count = currentShareCount(postId);
        return buildShareInfo(post, baseUrl, count);
    }

    /**
     * Append-only share record + counter bump + realtime broadcast + author
     * notification, returning the unified {@link ShareLinkInfo} so the caller
     * gets a fresh count alongside the link.
     */
    public ShareLinkInfo recordShareLink(UUID postId, UUID sharerId, String caption, String baseUrl) {
        PostByIdEntity post = postRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));
        recordShare(postId, sharerId, caption);
        long fresh = currentShareCount(postId);
        return buildShareInfo(post, baseUrl, fresh);
    }

    private long currentShareCount(UUID postId) {
        return postCounterRepo.findByPostId(postId)
                .map(c -> c.getShareCount() == null ? 0L : c.getShareCount())
                .orElse(0L);
    }

    private ShareLinkInfo buildShareInfo(PostByIdEntity post, String baseUrl, long count) {
        String backendBase = (baseUrl == null || baseUrl.isBlank())
                ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        String frontBase = frontendUrlResolver.resolve();
        if (frontBase == null || frontBase.isBlank()) frontBase = backendBase;
        String token = post.getId().toString();
        return new ShareLinkInfo(
                backendBase + "/p/" + token,
                frontBase + "/posts/" + token,
                token,
                count);
    }

    // ── realtime ─────────────────────────────────────────────────────────────

    private void broadcast(UUID postId, UUID actorId) {
        try {
            Long latest = postCounterRepo.findByPostId(postId)
                    .map(c -> c.getShareCount()).orElse(null);
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.SHARE_COUNT_UPDATED)
                    .postId(postId)
                    .actorId(actorId)
                    .postShareCount(latest)
                    .build());
        } catch (Exception e) {
            log.debug("[SHARE] realtime broadcast skipped: {}", e.getMessage());
        }
    }
}
