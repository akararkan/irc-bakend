package ak.dev.irc.app.post.service;



import ak.dev.irc.app.post.dto.CreatePostRequest;
import ak.dev.irc.app.post.dto.UpdatePostRequest;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.PostResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final PostMapper             postMapper;
    private final PostEventPublisher     eventPublisher;
    private final UserRepository         userRepository;
    private final UserFollowRepository   followRepository;
    private final UserBlockRepository    blockRepository;
    private final S3StorageService       storageService;

    // ── Create ────────────────────────────────────────────────

    @Transactional
    public PostResponse createPost(CreatePostRequest req, UUID authorId) {
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Post post = postMapper.toEntity(req, author);

        // Share link
        post.setShareLink(UUID.randomUUID().toString().replace("-", "").substring(0, 12));

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
                .shareLink(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
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

        // Decrement share count (min 0)
        if (original.getShareCount() > 0) {
            postRepository.updateReactionCount(original.getId(), 0); // placeholder; use dedicated query
        }

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
        log.info("Post updated: {} by user {}", postId, requesterId);
        return postMapper.toResponse(post);
    }

    // ── Read ──────────────────────────────────────────────────

    @Transactional
    public PostResponse getPost(UUID postId, UUID requesterId) {
        Post post = findPublishedPost(postId);
        postRepository.incrementViewCount(postId);

        PostReactionType myReaction = null;
        if (requesterId != null) {
            myReaction = reactionRepository.findByPostIdAndUserId(postId, requesterId)
                    .map(PostReaction::getReactionType)
                    .orElse(null);
        }
        return postMapper.toResponse(post, myReaction);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getPublicFeed(Pageable pageable) {
        return postRepository
                .findByStatusAndVisibilityOrderByCreatedAtDesc(PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                .map(postMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> getUserPosts(UUID authorId, Pageable pageable) {
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
        return postRepository
                .findByPostTypeAndStatusAndVisibilityOrderByCreatedAtDesc(
                        PostType.REEL, PostStatus.PUBLISHED, PostVisibility.PUBLIC, pageable)
                .map(postMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<PostResponse> searchPosts(String query, Pageable pageable) {
        return postRepository.search(query, pageable).map(postMapper::toResponse);
    }

    // ── React ─────────────────────────────────────────────────

    @Transactional
    public PostResponse reactToPost(UUID postId, UUID userId, ReactToPostRequest req) {
        Post post = findPublishedPost(postId);

        reactionRepository.findByPostIdAndUserId(postId, userId).ifPresentOrElse(existing -> {
            PostReactionType old = existing.getReactionType();
            existing.setReactionType(req.getReactionType());
            reactionRepository.save(existing);
            log.debug("Reaction changed from {} to {} on post {}", old, req.getReactionType(), postId);
        }, () -> {
            User user = userRepository.getReferenceById(userId);
            PostReaction reaction = PostReaction.builder()
                    .id(new PostReactionId(postId, userId))
                    .post(post)
                    .user(user)
                    .reactionType(req.getReactionType())
                    .build();
            reactionRepository.save(reaction);
            postRepository.updateReactionCount(postId, 1);
        });

        eventPublisher.publishPostReacted(PostReactedEvent.builder()
                .postId(postId)
                .reactorId(userId)
                .postAuthorId(post.getAuthor().getId())
                .reactionType(req.getReactionType().name())
                .build());

        return postMapper.toResponse(postRepository.findById(postId).orElseThrow());
    }

    @Transactional
    public void removeReaction(UUID postId, UUID userId) {
        reactionRepository.findByPostIdAndUserId(postId, userId).ifPresent(r -> {
            reactionRepository.delete(r);
            postRepository.updateReactionCount(postId, -1);
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
        post.setStatus(PostStatus.REMOVED);
        postRepository.save(post);
    }

    // ── Helpers ───────────────────────────────────────────────

    private List<UUID> getFilteredFollowingIds(UUID userId) {
        List<UUID> followingIds = followRepository.findFollowingIds(userId);
        if (followingIds.isEmpty()) return followingIds;
        // Remove users who have blocked the requester or whom the requester has blocked
        followingIds.removeIf(id -> blockRepository.isBlockedBetween(userId, id));
        return followingIds;
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
