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
import ak.dev.irc.app.post.enums.PostMediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
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

        // Story validation: text-only, single image, or single video ≤ 30s
        if (req.getPostType() == PostType.STORY) {
            validateStoryContent(req);
            post.setExpiresAt(LocalDateTime.now().plusHours(24));
        }

        // Share link
        post.setShareLink(UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        // Handle re-share
        if (req.getSharedPostId() != null) {
            Post original = postRepository.findById(UUID.fromString(req.getSharedPostId()))
                    .orElseThrow(() -> new EntityNotFoundException("Original post not found"));
            post.setSharedPost(original);
            postRepository.incrementShareCount(original.getId());
        }

        post = postRepository.save(post);

        // Publish RabbitMQ event (posts no longer carry voice)
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
     * Create a post with multipart file uploads (media images/videos + voice recording).
     * Files are uploaded to R2 storage and their URLs are set on the post entity.
     */
    @Transactional
    public PostResponse createPostWithFiles(CreatePostRequest req, UUID authorId,
                                            List<MultipartFile> files) {

        // Voice uploads removed for posts — only media files are processed here

        // Build media list from uploaded files
        if (files != null && !files.isEmpty()) {
            List<ak.dev.irc.app.post.dto.MediaItemRequest> mediaItems = new ArrayList<>();
            int order = 0;
            for (MultipartFile file : files) {
                String key = storageService.upload(file, "posts/media");
                String url = storageService.getPublicUrl(key);

                ak.dev.irc.app.post.dto.MediaItemRequest item = new ak.dev.irc.app.post.dto.MediaItemRequest();
                item.setUrl(url);
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
        if (req.getAudioTrackUrl() != null) post.setAudioTrackUrl(req.getAudioTrackUrl());
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
    public Page<PostResponse> getFollowingStoryFeed(UUID userId, Pageable pageable) {
        List<UUID> followingIds = getFilteredFollowingIds(userId);
        if (followingIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return postRepository.findFollowingStoryFeed(followingIds, LocalDateTime.now(), pageable)
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

    // ── Share ─────────────────────────────────────────────────

    @Transactional
    public PostResponse sharePost(UUID postId, UUID sharerId, String caption) {
        Post post = findPublishedPost(postId);
        User sharer = userRepository.getReferenceById(sharerId);

        PostShare share = PostShare.builder()
                .post(post)
                .sharer(sharer)
                .caption(caption)
                .sharePlatform("INTERNAL")
                .build();
        shareRepository.save(share);
        postRepository.incrementShareCount(postId);

        eventPublisher.publishPostShared(PostSharedEvent.builder()
                .postId(postId)
                .sharerId(sharerId)
                .postAuthorId(post.getAuthor().getId())
                .caption(caption)
                .build());

        return postMapper.toResponse(post);
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

    // ── Story expiration ─────────────────────────────────────

    /**
     * Runs every 60 seconds — marks expired stories as ARCHIVED
     * so they no longer appear in feeds or public queries.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void archiveExpiredStories() {
        List<Post> expired = postRepository.findExpiredStories(LocalDateTime.now());
        if (!expired.isEmpty()) {
            expired.forEach(p -> p.setStatus(PostStatus.ARCHIVED));
            postRepository.saveAll(expired);
            log.info("Archived {} expired stories", expired.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private void validateStoryContent(CreatePostRequest req) {
        if (req.getMediaList() == null || req.getMediaList().isEmpty()) {
            // Text-only story — valid as long as there's text content
            if (req.getTextContent() == null || req.getTextContent().isBlank()) {
                throw new BadRequestException(
                        "Story must have text content or a single media attachment",
                        "EMPTY_STORY");
            }
            return;
        }

        if (req.getMediaList().size() > 1) {
            throw new BadRequestException(
                    "Story can only have a single media attachment (one image or one video up to 30s)",
                    "STORY_MEDIA_LIMIT");
        }

        var media = req.getMediaList().get(0);
        if (media.getMediaType() == PostMediaType.VIDEO) {
            if (media.getDurationSeconds() != null && media.getDurationSeconds() > 30) {
                throw new BadRequestException(
                        "Story video must be 30 seconds or less",
                        "STORY_VIDEO_TOO_LONG");
            }
        } else if (media.getMediaType() != PostMediaType.IMAGE) {
            throw new BadRequestException(
                    "Story media must be an image or a video",
                    "STORY_INVALID_MEDIA_TYPE");
        }
    }

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
}