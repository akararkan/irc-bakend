package ak.dev.irc.app.post.service;



import ak.dev.irc.app.common.service.FollowingIdsCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.CreatePostRequest;
import ak.dev.irc.app.post.dto.CursorPage;
import ak.dev.irc.app.post.dto.UpdatePostRequest;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.realtime.PostRealtimeBroadcaster;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostViewTracker;
import ak.dev.irc.app.rabbitmq.event.user.MentionSource;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.*;
import ak.dev.irc.app.post.mapper.PostMapper;
import ak.dev.irc.app.post.repository.*;
import ak.dev.irc.app.rabbitmq.event.post.*;
import ak.dev.irc.app.rabbitmq.publisher.PostEventPublisher;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostService {

    private final PostRepository         postRepository;
    private final PostReactionRepository reactionRepository;
    private final PostShareRepository    shareRepository;
    private final PostSaveRepository     saveRepository;
    private final PostMapper             postMapper;
    private final PostEventPublisher     eventPublisher;
    private final UserRepository         userRepository;
    private final UserFollowRepository   followRepository;
    private final UserBlockRepository    blockRepository;
    private final S3StorageService       storageService;
    private final MentionService         mentionService;
    private final PostRealtimeBroadcaster realtime;
    private final PostViewTracker        viewTracker;
    private final SocialGuard            socialGuard;
    private final FollowingIdsCache      followingIdsCache;

    // Self-reference for proxy-mediated calls (so REQUIRES_NEW takes effect on internal calls).
    @Autowired @Lazy
    private PostService self;

    // ── Create ────────────────────────────────────────────────

    @Transactional
    public PostResponse createPost(CreatePostRequest req, UUID authorId) {
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Post post = postMapper.toEntity(req, author);

        // Share link
        post.setShareLink(generateUniqueShareLink());

        // Handle re-share via createPost (legacy path — prefer repostPost)
        if (req.getSharedPostId() != null) {
            Post original = postRepository.findById(UUID.fromString(req.getSharedPostId()))
                    .orElseThrow(() -> new EntityNotFoundException("Original post not found"));
            post.setSharedPost(resolveOriginal(original));
            postRepository.incrementShareCount(resolveOriginal(original).getId());
        }

        post = postRepository.save(post);

        // Publish RabbitMQ event
        eventPublisher.publishPostCreated(PostCreatedEvent.builder()
                .postId(post.getId())
                .authorId(authorId)
                .postType(post.getPostType().name())
                .visibility(post.getVisibility().name())
                .hasVoice(false)
                .build());

        // Scan @mentions and @followers in the post body. @followers fan-out is
        // allowed on creation only.
        mentionService.scanAndPublish(
                post.getTextContent(),
                MentionSource.POST,
                post.getId(),
                null,
                authorId,
                author.getUsername(),
                /* allowFollowersToken */ true);

        log.info("Post created: {} by user {}", post.getId(), authorId);
        return postMapper.toResponse(post);
    }

    /**
     * Create a post with multipart file uploads (media images/videos).
     * Files are uploaded to R2 storage and their URLs are set on the post entity.
     */
    @Transactional
    public PostResponse createPostWithFiles(CreatePostRequest req, UUID authorId,
                                            List<MultipartFile> files) {

        // Build media list from uploaded files
        if (files != null && !files.isEmpty()) {
            List<ak.dev.irc.app.post.dto.MediaItemRequest> mediaItems = new ArrayList<>();
            int order = 0;
            for (MultipartFile file : files) {
                String s3Key = storageService.upload(file, "posts/media");
                String url = storageService.getPublicUrl(s3Key);

                ak.dev.irc.app.post.dto.MediaItemRequest item = new ak.dev.irc.app.post.dto.MediaItemRequest();
                item.setUrl(url);
                item.setS3Key(s3Key);
                item.setMimeType(file.getContentType());
                item.setFileSizeBytes(file.getSize());
                item.setSortOrder(order++);

                // Determine media type from content type
                if (file.getContentType() != null && file.getContentType().startsWith("video/")) {
                    item.setMediaType(PostMediaType.VIDEO);
                } else {
                    item.setMediaType(PostMediaType.IMAGE);
                }

                mediaItems.add(item);
            }
            req.setMediaList(mediaItems);
        }

        // Delegate to the standard create method
        return createPost(req, authorId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REPOST / RESHARE (Facebook-style)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Repost/reshare a post. Creates a NEW Post (type=REPOST) in the sharer's feed
     * that references the original. The sharer's followers will see this in their feed.
     *
     * - If the target is itself a repost, we chain to the true original
     * - A user cannot repost the same original more than once
     * - A user CAN repost their own post (Twitter/Facebook-style self-share)
     */
    @Transactional
    public PostResponse repostPost(UUID postId, UUID sharerId, String caption) {
        Post target = findPublishedPost(postId);
        User sharer = userRepository.findById(sharerId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        // Resolve to the true original (skip repost chains)
        Post original = resolveOriginal(target);

        // Block guard — never let either side share content across a block.
        socialGuard.requireNotBlockedBetween(
                sharerId, original.getAuthor().getId(), "REPOST_BLOCKED_RELATIONSHIP");

        // Cannot repost the same original twice
        if (postRepository.findRepostByAuthorAndOriginal(sharerId, original.getId()).isPresent()) {
            throw new BadRequestException("You have already reposted this post", "DUPLICATE_REPOST");
        }

        // Create the repost as a new Post entity
        Post repost = Post.builder()
                .author(sharer)
                .postType(PostType.REPOST)
                .textContent(caption)
                .status(PostStatus.PUBLISHED)
                .visibility(PostVisibility.PUBLIC)
                .sharedPost(original)
                .shareLink(generateUniqueShareLink())
                .build();

        repost = postRepository.save(repost);

        // Increment share count on the original
        postRepository.incrementShareCount(original.getId());

        // Log the share event
        PostShare share = PostShare.builder()
                .post(original)
                .sharer(sharer)
                .caption(caption)
                .sharePlatform("INTERNAL")
                .build();
        shareRepository.save(share);

        // Publish events
        eventPublisher.publishPostCreated(PostCreatedEvent.builder()
                .postId(repost.getId())
                .authorId(sharerId)
                .postType(PostType.REPOST.name())
                .visibility(PostVisibility.PUBLIC.name())
                .hasVoice(false)
                .build());

        eventPublisher.publishPostShared(PostSharedEvent.builder()
                .postId(original.getId())
                .sharerId(sharerId)
                .postAuthorId(original.getAuthor().getId())
                .caption(caption)
                .build());

        Long freshShareCount = postRepository.findById(original.getId())
                .map(Post::getShareCount).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.SHARE_COUNT_UPDATED)
                .postId(original.getId())
                .actorId(sharerId)
                .actorUsername(sharer.getUsername())
                .actorAvatarUrl(sharer.getProfileImage())
                .postShareCount(freshShareCount)
                .build());

        log.info("Post reposted: original={} repost={} by user {}", original.getId(), repost.getId(), sharerId);
        return postMapper.toResponse(repost);
    }

    /**
     * Undo a repost. Removes the repost from the sharer's feed and decrements the share count.
     */
    @Transactional
    public void undoRepost(UUID postId, UUID requesterId) {
        Post target = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("Post not found"));
        Post original = resolveOriginal(target);

        Post repost = postRepository.findRepostByAuthorAndOriginal(requesterId, original.getId())
                .orElseThrow(() -> new BadRequestException("You have not reposted this post", "NO_REPOST"));

        if (!repost.getAuthor().getId().equals(requesterId)) {
            throw new AccessDeniedException("You can only undo your own reposts");
        }

        repost.setStatus(PostStatus.REMOVED);
        postRepository.save(repost);

        // Remove the share record
        shareRepository.findByPostIdAndSharerId(original.getId(), requesterId)
                .ifPresent(shareRepository::delete);

        postRepository.decrementShareCount(original.getId());

        Long freshShareCount = postRepository.findById(original.getId())
                .map(Post::getShareCount).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.SHARE_COUNT_UPDATED)
                .postId(original.getId())
                .actorId(requesterId)
                .postShareCount(freshShareCount)
                .build());

        log.info("Repost undone: original={} repost={} by user {}", original.getId(), repost.getId(), requesterId);
    }

    /**
     * Legacy share — logs the share event and increments counter.
     * Kept for external share tracking (copy link, share to WhatsApp, etc.)
     */
    @Transactional
    public PostResponse sharePost(UUID postId, UUID sharerId, String caption) {
        return repostPost(postId, sharerId, caption);
    }

    /**
     * Copy-link UX: atomically bumps the post's {@code shareCount}, broadcasts
     * the fresh count on the post realtime channel, and returns the canonical
     * share URL. Distinct from {@link #repostPost} — does NOT create a new
     * post in the sharer's feed; it just records that the link was copied
     * out for an external share (chat, email, social network, …).
     *
     * <p>Author can copy their own share link without a self-share record;
     * the count still bumps so they see external interest in their post.</p>
     */
    @Transactional
    public ShareLinkInfo copyShareLink(UUID postId, UUID requesterId, String baseUrl) {
        Post post = findPublishedPost(postId);
        // Always operate on the canonical original — copying a link to a
        // repost should still bump the original's share counter.
        Post original = resolveOriginal(post);

        if (requesterId != null) {
            socialGuard.requireNotBlockedBetween(
                    requesterId, original.getAuthor().getId(), "SHARE_BLOCKED_RELATIONSHIP");
        }

        postRepository.incrementShareCount(original.getId());

        Long fresh = postRepository.findById(original.getId())
                .map(Post::getShareCount).orElse(null);
        User actor = requesterId != null
                ? userRepository.findById(requesterId).orElse(null)
                : null;
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.SHARE_COUNT_UPDATED)
                .postId(original.getId())
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .postShareCount(fresh)
                .build());

        // Canonical short URL — uses the post's pre-generated 12-char token,
        // falls back to id if (somehow) missing.
        String token = original.getShareLink() != null && !original.getShareLink().isBlank()
                ? original.getShareLink()
                : original.getId().toString();
        String trimmedBase = (baseUrl == null || baseUrl.isBlank())
                ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        return new ShareLinkInfo(
                trimmedBase + "/p/" + token,
                trimmedBase + "/posts/" + original.getId(),
                token,
                fresh == null ? 0L : fresh);
    }

    /** Lightweight payload for the copy-link endpoint. */
    public record ShareLinkInfo(String shortUrl, String canonicalUrl, String token, long shareCount) {}

    // ── Update ────────────────────────────────────────────────

    @Transactional
    public PostResponse updatePost(UUID postId, UpdatePostRequest req, UUID requesterId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("Post not found"));

        if (!post.getAuthor().getId().equals(requesterId)) {
            throw new AccessDeniedException("You can only edit your own posts");
        }

        if (post.getStatus() == PostStatus.REMOVED) {
            throw new BadRequestException("Cannot edit a removed post", "POST_REMOVED");
        }

        // Capture body before mutation so the mention delta only fires for
        // newly-added @-handles (the ones the author wasn't already tagging).
        String previousBody = post.getTextContent();

        if (req.getTextContent() != null) post.setTextContent(req.getTextContent());
        if (req.getVisibility() != null) post.setVisibility(req.getVisibility());
        if (req.getAudioTrackUrl() != null) {
            post.setAudioTrackUrl(req.getAudioTrackUrl());
            post.setAudioTrackS3Key(req.getAudioTrackS3Key());
        }
        if (req.getAudioTrackName() != null) post.setAudioTrackName(req.getAudioTrackName());
        if (req.getLocationName() != null) post.setLocationName(req.getLocationName());
        if (req.getLocationLat() != null) post.setLocationLat(req.getLocationLat());
        if (req.getLocationLng() != null) post.setLocationLng(req.getLocationLng());

        post = postRepository.save(post);

        // Fan-out the edit so anyone reading the post sees the new content live.
        User author = post.getAuthor();
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.POST_UPDATED)
                .postId(post.getId())
                .actorId(author.getId())
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .textContent(post.getTextContent())
                .build());

        // Notify any users newly @-mentioned by this edit (delta only — does
        // not re-notify users that were already tagged in the previous body).
        mentionService.scanAndPublishDelta(
                previousBody,
                post.getTextContent(),
                MentionSource.POST,
                post.getId(),
                null,
                requesterId,
                author.getUsername());

        log.info("Post updated: {} by user {}", postId, requesterId);
        return postMapper.toResponse(post);
    }

    // ── Read ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PostResponse getPost(UUID postId, UUID requesterId) {
        return getPost(postId, requesterId, requesterId != null ? requesterId.toString() : null);
    }

    /**
     * Read a post and bump the live view counter.
     *
     * @param viewerKey opaque fingerprint used to dedupe rapid refresh hits
     *                  (typically userId.toString() for authed users, the client
     *                  IP for anonymous readers). Null disables dedupe entirely.
     */
    /**
     * Visibility check shared between {@link #getPost} and the SSE stream
     * endpoint. Throws {@link EntityNotFoundException} for missing or
     * blocked-from-viewer posts so the response is identical in either case
     * (no information leak about why the post is unavailable).
     */
    @Transactional(readOnly = true)
    public void assertPostVisible(UUID postId, UUID requesterId) {
        Post post = findPublishedPost(postId);
        if (requesterId != null
                && socialGuard.isBlockedBetween(requesterId, post.getAuthor().getId())) {
            throw new EntityNotFoundException("Post not found or unavailable");
        }
    }

    @Transactional(readOnly = true)
    public PostResponse getPost(UUID postId, UUID requesterId, String viewerKey) {
        Post post = findPublishedPost(postId);

        // Hide the post entirely from anyone in a block-relationship with the
        // author — pretend the post does not exist so a blocker cannot probe.
        if (requesterId != null
                && socialGuard.isBlockedBetween(requesterId, post.getAuthor().getId())) {
            throw new EntityNotFoundException("Post not found or unavailable");
        }

        PostReactionType myReaction = null;
        boolean isSaved = false;
        if (requesterId != null) {
            myReaction = reactionRepository.findByPostIdAndUserId(postId, requesterId)
                    .map(PostReaction::getReactionType)
                    .orElse(null);
            isSaved = saveRepository.existsById(new PostSaveId(postId, requesterId));
        }
        // View counter bumped in a separate write transaction so the read path
        // stays readOnly and never blocks on a write lock.
        // Routed through the proxy (`self`) so REQUIRES_NEW actually applies.
        self.recordView(postId, requesterId, viewerKey);
        return postMapper.toResponse(post, myReaction, isSaved);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordView(UUID postId, UUID viewerId, String viewerKey) {
        try {
            // Dedupe: refresh-spam from the same viewer in the dedupe window is ignored.
            if (!viewTracker.shouldCount(postId, viewerKey)) return;

            postRepository.incrementViewCount(postId);

            // Read fresh count and broadcast — registered as afterCommit by the broadcaster
            // so subscribers never see a count that the DB is about to roll back.
            Long freshCount = postRepository.findById(postId).map(Post::getViewCount).orElse(null);
            if (freshCount != null) {
                realtime.broadcast(PostRealtimeEvent.builder()
                        .eventType(PostRealtimeEventType.VIEW_COUNT_UPDATED)
                        .postId(postId)
                        .actorId(viewerId)
                        .postViewCount(freshCount)
                        .build());
            }
        } catch (Exception e) {
            // View counts are best-effort; never let a counter failure break a read.
            log.warn("Failed to bump view count for post {}: {}", postId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getPublicFeed(Pageable pageable) {
        return getPublicFeed(null, pageable);
    }

    /**
     * Public feed — block-aware when {@code viewerId} is provided.
     *
     * <p>Anonymous viewers ({@code viewerId == null}) see the unfiltered feed
     * because there is no block relationship to honour. Authenticated viewers
     * receive the same feed minus any author they are in a block-relationship
     * with — done in one SQL via {@code findPublicFeedExcluding}.</p>
     */
    @Transactional(readOnly = true)
    public Page<PostResponse> getPublicFeed(UUID viewerId, Pageable pageable) {
        if (viewerId == null) {
            return postRepository
                    .findByStatusAndVisibilityOrderByCreatedAtDesc(PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                    .map(postMapper::toResponse);
        }
        List<UUID> blocked = socialGuard.findRelatedBlockedIds(viewerId);
        if (blocked.isEmpty()) {
            return postRepository
                    .findByStatusAndVisibilityOrderByCreatedAtDesc(PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                    .map(postMapper::toResponse);
        }
        return postRepository.findPublicFeedExcluding(blocked, pageable)
                .map(postMapper::toResponse);
    }

    /**
     * Cursor-paginated public feed. Pass {@code cursor=null} for the first page;
     * for subsequent pages pass the {@code nextCursor} from the previous response.
     * O(log n) deep paging — does not degrade as the user scrolls.
     */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> getPublicFeedCursor(LocalDateTime cursor, int limit) {
        return getPublicFeedCursor(null, cursor, limit);
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> getPublicFeedCursor(UUID viewerId, LocalDateTime cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        // Fetch one extra to determine if there are more pages without an extra COUNT.
        var pageReq = org.springframework.data.domain.PageRequest.of(0, safeLimit + 1);

        List<UUID> blocked = viewerId != null ? socialGuard.findRelatedBlockedIds(viewerId) : List.of();
        boolean useFilter = !blocked.isEmpty();

        List<Post> rows;
        if (cursor == null) {
            rows = useFilter
                    ? postRepository.findPublicFeedFirstPageExcluding(blocked, pageReq)
                    : postRepository.findPublicFeedFirstPage(pageReq);
        } else {
            rows = useFilter
                    ? postRepository.findPublicFeedAfterExcluding(cursor, blocked, pageReq)
                    : postRepository.findPublicFeedAfter(cursor, pageReq);
        }

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);

        List<PostResponse> items = rows.stream().map(postMapper::toResponse).toList();
        LocalDateTime nextCursor = hasMore && !items.isEmpty()
                ? rows.get(rows.size() - 1).getCreatedAt()
                : null;

        return CursorPage.<PostResponse>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getUserPosts(UUID authorId, Pageable pageable) {
        return getUserPosts(authorId, null, pageable);
    }

    /**
     * Author profile feed — refuses to disclose anything when the requester is
     * in a block-relationship with the author. Returns 403 instead of an
     * empty page so the caller can render a clear "this profile is unavailable"
     * state.
     */
    @Transactional(readOnly = true)
    public Page<PostResponse> getUserPosts(UUID authorId, UUID requesterId, Pageable pageable) {
        if (requesterId != null
                && !requesterId.equals(authorId)
                && socialGuard.isBlockedBetween(requesterId, authorId)) {
            throw new ForbiddenException(
                    "This profile is not available.",
                    "PROFILE_BLOCKED_RELATIONSHIP");
        }
        return postRepository
                .findByAuthorIdAndStatusAndVisibility(authorId, PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                .map(postMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getFollowingFeed(UUID userId, Pageable pageable) {
        List<UUID> followingIds = getFilteredFollowingIds(userId);
        if (followingIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return postRepository.findFollowingFeed(followingIds, pageable)
                .map(postMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getFollowingReelFeed(UUID userId, Pageable pageable) {
        List<UUID> followingIds = getFilteredFollowingIds(userId);
        if (followingIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return postRepository.findFollowingReelFeed(followingIds, pageable)
                .map(postMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getReelFeed(Pageable pageable) {
        return getReelFeed(null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getReelFeed(UUID viewerId, Pageable pageable) {
        if (viewerId == null) {
            return postRepository
                    .findByPostTypeAndStatusAndVisibilityOrderByCreatedAtDesc(
                            PostType.REEL, PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                    .map(postMapper::toResponse);
        }
        List<UUID> blocked = socialGuard.findRelatedBlockedIds(viewerId);
        if (blocked.isEmpty()) {
            return postRepository
                    .findByPostTypeAndStatusAndVisibilityOrderByCreatedAtDesc(
                            PostType.REEL, PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                    .map(postMapper::toResponse);
        }
        return postRepository.findReelFeedExcluding(blocked, pageable)
                .map(postMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> searchPosts(String query, Pageable pageable) {
        return searchPosts(query, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> searchPosts(String query, UUID viewerId, Pageable pageable) {
        if (viewerId == null) {
            return postRepository.search(query, pageable).map(postMapper::toResponse);
        }
        List<UUID> blocked = socialGuard.findRelatedBlockedIds(viewerId);
        if (blocked.isEmpty()) {
            return postRepository.search(query, pageable).map(postMapper::toResponse);
        }
        return postRepository.searchExcluding(query, blocked, pageable).map(postMapper::toResponse);
    }

    // ── React ─────────────────────────────────────────────────

    @Transactional
    public PostResponse reactToPost(UUID postId, UUID userId, ReactToPostRequest req) {
        Post post = findPublishedPost(postId);

        // Block guard — disallow reacting across any block edge.
        socialGuard.requireNotBlockedBetween(
                userId, post.getAuthor().getId(), "REACTION_BLOCKED_RELATIONSHIP");

        // Single LIKE-style reaction — repeated calls are idempotent.
        if (reactionRepository.findByPostIdAndUserId(postId, userId).isPresent()) {
            return postMapper.toResponse(post, PostReactionType.LIKE);
        }

        User user = userRepository.getReferenceById(userId);
        PostReaction reaction = PostReaction.builder()
                .id(new PostReactionId(postId, userId))
                .post(post)
                .user(user)
                .reactionType(PostReactionType.LIKE)
                .build();
        reactionRepository.save(reaction);
        postRepository.updateReactionCount(postId, 1);

        eventPublisher.publishPostReacted(PostReactedEvent.builder()
                .postId(postId)
                .reactorId(userId)
                .postAuthorId(post.getAuthor().getId())
                .reactionType(PostReactionType.LIKE.name())
                .build());

        Post fresh = postRepository.findById(postId).orElseThrow();
        User actor = userRepository.findById(userId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.REACTION_ADDED)
                .postId(postId)
                .actorId(userId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .reactionType(PostReactionType.LIKE.name())
                .postReactionCount(fresh.getReactionCount())
                .build());

        return postMapper.toResponse(fresh, PostReactionType.LIKE);
    }

    @Transactional
    public void removeReaction(UUID postId, UUID userId) {
        reactionRepository.findByPostIdAndUserId(postId, userId).ifPresent(r -> {
            PostReactionType previous = r.getReactionType();
            reactionRepository.delete(r);
            postRepository.updateReactionCount(postId, -1);

            eventPublisher.publishPostUnreacted(postId, userId, previous.name());

            Post fresh = postRepository.findById(postId).orElse(null);
            User actor = userRepository.findById(userId).orElse(null);
            realtime.broadcast(PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.REACTION_REMOVED)
                    .postId(postId)
                    .actorId(userId)
                    .actorUsername(actor != null ? actor.getUsername() : null)
                    .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                    .previousReactionType(previous.name())
                    .postReactionCount(fresh != null ? fresh.getReactionCount() : null)
                    .build());
        });
    }

    // ── Delete ────────────────────────────────────────────────

    @Transactional
    public void deletePost(UUID postId, UUID requesterId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("Post not found"));
        if (!post.getAuthor().getId().equals(requesterId)) {
            throw new AccessDeniedException("You can only delete your own posts");
        }

        // If the deleted post is a repost, roll back the share-count bump on the
        // original so the original's share counter reflects only live reposts.
        Post original = null;
        if (post.getPostType() == PostType.REPOST && post.getSharedPost() != null) {
            original = post.getSharedPost();
            shareRepository.findByPostIdAndSharerId(original.getId(), requesterId)
                    .ifPresent(shareRepository::delete);
            postRepository.decrementShareCount(original.getId());
        }

        post.setStatus(PostStatus.REMOVED);
        postRepository.save(post);

        eventPublisher.publishPostDeleted(postId, requesterId);

        User author = post.getAuthor();
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.POST_DELETED)
                .postId(postId)
                .actorId(author.getId())
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .build());

        // Fan the fresh share count out on the original's stream so anyone
        // looking at the original sees the counter drop live.
        if (original != null) {
            UUID originalId = original.getId();
            Long freshShareCount = postRepository.findById(originalId)
                    .map(Post::getShareCount).orElse(null);
            realtime.broadcast(PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.SHARE_COUNT_UPDATED)
                    .postId(originalId)
                    .actorId(requesterId)
                    .actorUsername(author.getUsername())
                    .actorAvatarUrl(author.getProfileImage())
                    .postShareCount(freshShareCount)
                    .build());
        }

        log.info("Post deleted: {} by user {}", postId, requesterId);
    }

    // ── Save / Bookmark ───────────────────────────────────────

    @Transactional
    public void savePost(UUID postId, UUID userId, String collectionName) {
        Post post = findPublishedPost(postId);

        socialGuard.requireNotBlockedBetween(
                userId, post.getAuthor().getId(), "SAVE_BLOCKED_RELATIONSHIP");

        PostSaveId sid = new PostSaveId(postId, userId);
        if (saveRepository.existsById(sid)) return; // idempotent

        User user = userRepository.getReferenceById(userId);
        saveRepository.save(PostSave.builder()
                .id(sid).post(post).user(user)
                .collectionName(collectionName != null && !collectionName.isBlank()
                        ? collectionName.trim() : "Default")
                .build());
        postRepository.adjustSaveCount(postId, 1);

        Long freshSaveCount = postRepository.findById(postId)
                .map(Post::getSaveCount).orElse(null);
        User actor = userRepository.findById(userId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.SAVE_COUNT_UPDATED)
                .postId(postId)
                .actorId(userId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .postSaveCount(freshSaveCount)
                .build());
    }

    @Transactional
    public void unsavePost(UUID postId, UUID userId) {
        PostSaveId sid = new PostSaveId(postId, userId);
        if (!saveRepository.existsById(sid)) return; // idempotent
        saveRepository.deleteById(sid);
        postRepository.adjustSaveCount(postId, -1);

        Long freshSaveCount = postRepository.findById(postId)
                .map(Post::getSaveCount).orElse(null);
        User actor = userRepository.findById(userId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.SAVE_COUNT_UPDATED)
                .postId(postId)
                .actorId(userId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .postSaveCount(freshSaveCount)
                .build());
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getSavedPosts(UUID userId, Pageable pageable) {
        return saveRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(s -> postMapper.toResponse(s.getPost(), null, true));
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getSavedPostsByCollection(UUID userId, String collectionName,
                                                       Pageable pageable) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new BadRequestException("Collection name is required", "MISSING_COLLECTION_NAME");
        }
        return saveRepository.findByUserIdAndCollectionNameOrderByCreatedAtDesc(
                        userId, collectionName.trim(), pageable)
                .map(s -> postMapper.toResponse(s.getPost(), null, true));
    }

    @Transactional(readOnly = true)
    public List<String> getUserPostCollections(UUID userId) {
        return saveRepository.findDistinctCollectionNamesByUserId(userId);
    }

    @Transactional
    public void renamePostCollection(UUID userId, String oldName, String newName) {
        if (oldName == null || oldName.isBlank())
            throw new BadRequestException("Old collection name is required", "MISSING_OLD_NAME");
        if (newName == null || newName.isBlank())
            throw new BadRequestException("New collection name is required", "MISSING_NEW_NAME");
        saveRepository.renameCollection(userId, oldName, newName.trim());
    }

    // ── Helpers ───────────────────────────────────────────────

    private List<UUID> getFilteredFollowingIds(UUID userId) {
        // Routes through the Spring proxy on FollowingIdsCache so the
        // 1-minute Redis cache is honoured. Cache is evicted on
        // follow / unfollow / (un)block in UserSocialServiceImpl.
        return followingIdsCache.getFilteredFollowingIds(userId);
    }

    private String generateUniqueShareLink() {
        for (int i = 0; i < 8; i++) {
            String candidate = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (!postRepository.existsByShareLink(candidate)) return candidate;
        }
        // Vanishingly unlikely fallback: full UUID is collision-resistant.
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Post findPublishedPost(UUID postId) {
        return postRepository.findById(postId)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException("Post not found or unavailable"));
    }

    /**
     * If the post is itself a repost, follow the chain to the true original.
     * This prevents repost-of-repost chains: everyone reposts the original.
     */
    private Post resolveOriginal(Post post) {
        if (post.getPostType() == PostType.REPOST && post.getSharedPost() != null) {
            return post.getSharedPost();
        }
        return post;
    }
}
