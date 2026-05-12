package ak.dev.irc.app.post.service;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.service.MentionService;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.post.dto.CreateCommentRequest;
import ak.dev.irc.app.post.dto.EditCommentRequest;
import ak.dev.irc.app.rabbitmq.event.user.MentionSource;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.mapper.PostMapper;
import ak.dev.irc.app.post.realtime.PostRealtimeBroadcaster;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.repository.*;
import ak.dev.irc.app.rabbitmq.event.post.PostCommentReactedEvent;
import ak.dev.irc.app.rabbitmq.event.post.PostCommentedEvent;
import ak.dev.irc.app.rabbitmq.publisher.PostEventPublisher;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostCommentService {

    private final PostRepository              postRepository;
    private final PostCommentRepository       commentRepository;
    private final PostCommentReactionRepository commentReactionRepository;
    private final PostMapper                  postMapper;
    private final PostEventPublisher          eventPublisher;
    private final UserRepository              userRepository;
    private final S3StorageService            storageService;
    private final MentionService              mentionService;
    private final PostRealtimeBroadcaster     realtime;
    private final SocialGuard                 socialGuard;
    private final CounterCache                counterCache;

    // Needed to refresh entities after JPQL bulk-updates so SSE broadcasts
    // carry the post-increment counter, not the pre-increment cached value.
    @PersistenceContext
    private EntityManager em;

    // ── Add comment / reply ───────────────────────────────────

    @Transactional
    public CommentResponse addComment(UUID postId, UUID authorId, CreateCommentRequest req) {
        Post post = postRepository.findById(postId)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException("Post not found"));

        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        // Block guard — refuse comments / replies across any block edge.
        socialGuard.requireNotBlockedBetween(
                authorId, post.getAuthor().getId(), "COMMENT_BLOCKED_RELATIONSHIP");

        PostComment parent = null;
        if (req.getParentId() != null) {
            parent = commentRepository.findById(req.getParentId())
                    .orElseThrow(() -> new EntityNotFoundException("Parent comment not found"));
            if (!parent.getPost().getId().equals(postId)) {
                throw new BadRequestException("Parent comment does not belong to this post", "INVALID_PARENT");
            }
            if (parent.isDeleted()) {
                throw new BadRequestException("Cannot reply to a deleted comment", "PARENT_DELETED");
            }
            // A reply must also clear the block check against the parent comment author.
            socialGuard.requireNotBlockedBetween(
                    authorId, parent.getAuthor().getId(), "REPLY_BLOCKED_RELATIONSHIP");
            commentRepository.updateReplyCount(parent.getId(), 1);
        }

        PostComment comment = PostComment.builder()
                .post(post)
                .author(author)
                .parent(parent)
                .textContent(req.getTextContent())
                .mediaUrl(req.getMediaUrl())
                .mediaS3Key(req.getMediaS3Key())
                .mediaType(req.getMediaType())
                .mediaThumbnailUrl(req.getMediaThumbnailUrl())
                .mediaThumbnailS3Key(req.getMediaThumbnailS3Key())
                // Voice comments are disabled for posts — do not set voice fields on the entity
                .build();

        comment = commentRepository.save(comment);
        postRepository.updateCommentCount(postId, 1);

        boolean isReply = parent != null;
        UUID parentCommentId = parent != null ? parent.getId() : null;
        UUID parentCommentAuthorId = parent != null ? parent.getAuthor().getId() : null;
        eventPublisher.publishPostCommented(PostCommentedEvent.builder()
                .postId(postId)
                .commentId(comment.getId())
                .commentAuthorId(authorId)
                .postAuthorId(post.getAuthor().getId())
                .parentCommentId(parentCommentId)
                .parentCommentAuthorId(parentCommentAuthorId)
                .isReply(isReply)
                .hasVoice(false)
                .build());

        // @mentions inside comments. @followers is intentionally NOT honoured
        // here — comments would otherwise become a spam vector.
        mentionService.scanAndPublish(
                comment.getTextContent(),
                MentionSource.POST_COMMENT,
                comment.getId(),
                postId,
                authorId,
                author.getUsername(),
                /* allowFollowersToken */ false);

        // Refresh the L1 cache so we read post-increment counters, then write
        // through to Redis so the feed and detail pages see the live numbers.
        em.refresh(post);
        counterCache.set(CounterCache.Kind.POST, postId,
                CounterCache.F_COMMENTS, post.getCommentCount());
        Long parentReplyCount = null;
        if (parent != null) {
            em.refresh(parent);
            parentReplyCount = parent.getReplyCount();
            counterCache.set(CounterCache.Kind.POST_COMMENT, parent.getId(),
                    CounterCache.F_REPLIES, parentReplyCount);
        }

        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(isReply ? PostRealtimeEventType.REPLY_CREATED
                                   : PostRealtimeEventType.COMMENT_CREATED)
                .postId(postId)
                .actorId(authorId)
                .actorUsername(author.getUsername())
                .actorAvatarUrl(author.getProfileImage())
                .commentId(comment.getId())
                .parentCommentId(parentCommentId)
                .textContent(comment.getTextContent())
                .mediaUrl(comment.getMediaUrl())
                .mediaType(comment.getMediaType())
                .mediaThumbnailUrl(comment.getMediaThumbnailUrl())
                .postCommentCount(post.getCommentCount())
                .commentReplyCount(parentReplyCount)
                .build());

        return postMapper.toCommentResponse(comment);
    }

    /**
     * Add a comment with optional media/voice file uploads.
     * Files are uploaded to R2 storage and their URLs are set on the request before delegating.
     */
    @Transactional
    public CommentResponse addCommentWithMedia(UUID postId, UUID authorId,
                                               CreateCommentRequest req,
                                               MultipartFile media) {
        // Upload media file if present (image or video attachment on a comment)
        if (media != null && !media.isEmpty()) {
            String mediaKey = storageService.upload(media, "posts/comments/media");
            req.setMediaUrl(storageService.getPublicUrl(mediaKey));
            req.setMediaS3Key(mediaKey);
            String contentType = media.getContentType();
            if (contentType != null && contentType.startsWith("video")) {
                req.setMediaType("VIDEO");
            } else {
                req.setMediaType("IMAGE");
            }
        }

        // Ensure request contains only media/text fields for posts
        return addComment(postId, authorId, req);
    }

    /**
     * Compatibility overload: accept an optional voice file but ignore it.
     * This preserves callers that still send a voice MultipartFile while
     * voice comments are disabled.
     */
    // Compatibility overload removed: voice MultipartFile support for post comments is disabled.

    // ── Read ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CommentResponse> getTopLevelComments(UUID postId, UUID requesterId, Pageable pageable) {
        return commentRepository
                .findVisibleTopLevelComments(postId, requesterId, pageable)
                .map(c -> {
                    PostReactionType myReaction = requesterId != null
                            ? commentReactionRepository.findByCommentIdAndUserId(c.getId(), requesterId)
                            .map(PostCommentReaction::getReactionType).orElse(null)
                            : null;
                    return postMapper.toCommentResponse(c, myReaction);
                });
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> getReplies(UUID commentId, UUID requesterId, Pageable pageable) {
        return commentRepository
                .findVisibleReplies(commentId, requesterId, pageable)
                .map(c -> {
                    PostReactionType myReaction = requesterId != null
                            ? commentReactionRepository.findByCommentIdAndUserId(c.getId(), requesterId)
                            .map(PostCommentReaction::getReactionType).orElse(null)
                            : null;
                    return postMapper.toCommentResponse(c, myReaction);
                });
    }

    // ── React to comment ──────────────────────────────────────

    @Transactional
    public CommentResponse reactToComment(UUID commentId, UUID userId, ReactToPostRequest req) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));

        // Block guards — disallow reacting either to a blocked comment author
        // or to a comment on a blocked post author's post.
        socialGuard.requireNotBlockedBetween(
                userId, comment.getAuthor().getId(), "COMMENT_REACTION_BLOCKED_RELATIONSHIP");
        socialGuard.requireNotBlockedBetween(
                userId, comment.getPost().getAuthor().getId(), "COMMENT_REACTION_BLOCKED_RELATIONSHIP");

        User actor = userRepository.findById(userId).orElse(null);

        // Re-react is a no-op at the DB layer; still emit the authoritative
        // count so an SSE-driven UI can reconcile.
        if (commentReactionRepository.findByCommentIdAndUserId(commentId, userId).isPresent()) {
            long current = counterCache.getOr(CounterCache.Kind.POST_COMMENT, commentId,
                    CounterCache.F_REACTIONS, comment::getReactionCount);
            realtime.broadcast(PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.COMMENT_REACTION_ADDED)
                    .postId(comment.getPost().getId())
                    .actorId(userId)
                    .actorUsername(actor != null ? actor.getUsername() : null)
                    .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                    .commentId(commentId)
                    .reactionType(PostReactionType.LIKE.name())
                    .commentReactionCount(current)
                    .build());
            return postMapper.toCommentResponse(comment, PostReactionType.LIKE);
        }

        User user = userRepository.getReferenceById(userId);
        PostCommentReaction reaction = PostCommentReaction.builder()
                .id(new PostCommentReactionId(commentId, userId))
                .comment(comment)
                .user(user)
                .reactionType(PostReactionType.LIKE)
                .build();
        commentReactionRepository.save(reaction);
        commentRepository.updateReactionCount(commentId, 1);
        // JPQL bulk UPDATE bypasses the L1 cache — refresh, then write through.
        em.refresh(comment);
        counterCache.set(CounterCache.Kind.POST_COMMENT, commentId,
                CounterCache.F_REACTIONS, comment.getReactionCount());

        eventPublisher.publishCommentReacted(PostCommentReactedEvent.builder()
                .commentId(commentId)
                .reactorId(userId)
                .commentAuthorId(comment.getAuthor().getId())
                .postId(comment.getPost().getId())
                .reactionType(PostReactionType.LIKE.name())
                .build());

        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.COMMENT_REACTION_ADDED)
                .postId(comment.getPost().getId())
                .actorId(userId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .commentId(commentId)
                .reactionType(PostReactionType.LIKE.name())
                .commentReactionCount(comment.getReactionCount())
                .build());

        return postMapper.toCommentResponse(comment, PostReactionType.LIKE);
    }

    @Transactional
    public void removeCommentReaction(UUID commentId, UUID userId) {
        commentReactionRepository.findByCommentIdAndUserId(commentId, userId).ifPresent(r -> {
            PostComment comment = r.getComment();
            UUID postId = comment.getPost().getId();
            commentReactionRepository.delete(r);
            commentRepository.updateReactionCount(commentId, -1);
            em.refresh(comment);
            counterCache.set(CounterCache.Kind.POST_COMMENT, commentId,
                    CounterCache.F_REACTIONS, comment.getReactionCount());

            User actor = userRepository.findById(userId).orElse(null);
            realtime.broadcast(PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.COMMENT_REACTION_REMOVED)
                    .postId(postId)
                    .actorId(userId)
                    .actorUsername(actor != null ? actor.getUsername() : null)
                    .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                    .commentId(commentId)
                    .commentReactionCount(comment.getReactionCount())
                    .build());
        });
    }

    // ── Delete comment ────────────────────────────────────────

    @Transactional
    public void deleteComment(UUID postId, UUID commentId, UUID requesterId) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));
        if (!comment.getPost().getId().equals(postId)) {
            throw new BadRequestException("Comment does not belong to this post", "INVALID_COMMENT");
        }
        if (comment.isDeleted()) {
            throw new BadRequestException("Comment is already deleted", "ALREADY_DELETED");
        }
        if (!comment.getAuthor().getId().equals(requesterId)) {
            throw new AccessDeniedException("You can only delete your own comments");
        }
        // Counter cleanup — drop every reaction on this comment so the
        // soft-deleted comment shows reactionCount=0 if ever re-rendered, and
        // the per-user "I reacted" state for other viewers goes away too.
        commentReactionRepository.deleteAllByCommentId(commentId);
        comment.setReactionCount(0L);

        comment.setIsDeleted(true);
        comment.setDeletedAt(LocalDateTime.now());
        commentRepository.save(comment);
        Post post = comment.getPost();
        postRepository.updateCommentCount(post.getId(), -1);
        em.refresh(post);
        counterCache.set(CounterCache.Kind.POST, post.getId(),
                CounterCache.F_COMMENTS, post.getCommentCount());
        // The deleted comment's own reaction counter is zeroed above.
        counterCache.set(CounterCache.Kind.POST_COMMENT, commentId,
                CounterCache.F_REACTIONS, 0L);
        UUID parentId = null;
        Long parentReplyCount = null;
        if (comment.getParent() != null) {
            PostComment parent = comment.getParent();
            parentId = parent.getId();
            commentRepository.updateReplyCount(parentId, -1);
            em.refresh(parent);
            parentReplyCount = parent.getReplyCount();
            counterCache.set(CounterCache.Kind.POST_COMMENT, parentId,
                    CounterCache.F_REPLIES, parentReplyCount);
        }

        eventPublisher.publishPostCommentDeleted(postId, commentId, parentId, requesterId);

        User actor = userRepository.findById(requesterId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.COMMENT_DELETED)
                .postId(postId)
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .commentId(commentId)
                .parentCommentId(parentId)
                .postCommentCount(post.getCommentCount())
                .commentReplyCount(parentReplyCount)
                .build());
    }

    @Transactional
    public CommentResponse editComment(UUID postId, UUID commentId, UUID requesterId, EditCommentRequest req) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));

        if (!comment.getPost().getId().equals(postId)) {
            throw new BadRequestException("Comment does not belong to this post", "INVALID_COMMENT");
        }
        if (comment.isDeleted()) {
            throw new BadRequestException("Cannot edit a deleted comment", "COMMENT_DELETED");
        }
        if (!comment.getAuthor().getId().equals(requesterId)) {
            throw new AccessDeniedException("You can only edit your own comments");
        }

        String previousBody = comment.getTextContent();
        comment.setTextContent(req.getTextContent().trim());
        comment.setEdited(true);
        comment.setEditedAt(LocalDateTime.now());
        commentRepository.save(comment);

        User actor = userRepository.findById(requesterId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.COMMENT_EDITED)
                .postId(postId)
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .commentId(commentId)
                .textContent(comment.getTextContent())
                .build());

        // Notify any newly @-mentioned users introduced by this edit only.
        mentionService.scanAndPublishDelta(
                previousBody,
                comment.getTextContent(),
                MentionSource.POST_COMMENT,
                comment.getId(),
                postId,
                requesterId,
                actor != null ? actor.getUsername() : null);

        return postMapper.toCommentResponse(comment);
    }
}