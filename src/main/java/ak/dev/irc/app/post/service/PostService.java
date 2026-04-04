package ak.dev.irc.app.post.service;



import ak.dev.irc.app.post.dto.CreatePostRequest;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.*;
import ak.dev.irc.app.post.mapper.PostMapper;
import ak.dev.irc.app.post.repository.*;
import ak.dev.irc.app.rabbitmq.event.post.*;
import ak.dev.irc.app.rabbitmq.publisher.PostEventPublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    // ── Create ────────────────────────────────────────────────

    @Transactional
    public PostResponse createPost(CreatePostRequest req, UUID authorId) {
        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Post post = postMapper.toEntity(req, author);

        // Story TTL: auto-expire in 24 h
        if (req.getPostType() == PostType.STORY) {
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

        // Publish RabbitMQ event
        eventPublisher.publishPostCreated(PostCreatedEvent.builder()
                .postId(post.getId())
                .authorId(authorId)
                .postType(post.getPostType().name())
                .visibility(post.getVisibility().name())
                .hasVoice(post.getVoiceUrl() != null)
                .build());

        log.info("Post created: {} by user {}", post.getId(), authorId);
        return postMapper.toResponse(post);
    }

    // ── Read ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
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
    public void expireStories() {
        var expired = postRepository.findExpiredStories(LocalDateTime.now());
        for (Post story : expired) {
            story.setStatus(PostStatus.ARCHIVED);
            postRepository.save(story);
            log.debug("Story expired and archived: {}", story.getId());
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} storie(s)", expired.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private Post findPublishedPost(UUID postId) {
        return postRepository.findById(postId)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException("Post not found or unavailable"));
    }
}