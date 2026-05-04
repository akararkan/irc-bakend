package ak.dev.irc.app.post.service;

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
import jakarta.persistence.EntityNotFoundException;
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

        Post fresh = postRepository.findById(postId).orElse(post);
        Long parentReplyCount = parent != null
                ? commentRepository.findById(parent.getId()).map(PostComment::getReplyCount).orElse(null)
                : null;

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
                .postCommentCount(fresh.getCommentCount())
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

        var existingOpt = commentReactionRepository.findByCommentIdAndUserId(commentId, userId);
        boolean isChange = existingOpt.isPresent();
        PostReactionType previous = existingOpt.map(PostCommentReaction::getReactionType).orElse(null);

        if (isChange) {
            PostCommentReaction existing = existingOpt.get();
            existing.setReactionType(req.getReactionType());
            commentReactionRepository.save(existing);
        } else {
            User user = userRepository.getReferenceById(userId);
            PostCommentReaction reaction = PostCommentReaction.builder()
                    .id(new PostCommentReactionId(commentId, userId))
                    .comment(comment)
                    .user(user)
                    .reactionType(req.getReactionType())
                    .build();
            commentReactionRepository.save(reaction);
            commentRepository.updateReactionCount(commentId, 1);
        }

        eventPublisher.publishCommentReacted(PostCommentReactedEvent.builder()
                .commentId(commentId)
                .reactorId(userId)
                .commentAuthorId(comment.getAuthor().getId())
                .postId(comment.getPost().getId())
                .reactionType(req.getReactionType().name())
                .build());

        PostComment fresh = commentRepository.findById(commentId).orElseThrow();
        User actor = userRepository.findById(userId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(isChange ? PostRealtimeEventType.COMMENT_REACTION_CHANGED
                                    : PostRealtimeEventType.COMMENT_REACTION_ADDED)
                .postId(comment.getPost().getId())
                .actorId(userId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .commentId(commentId)
                .reactionType(req.getReactionType().name())
                .previousReactionType(previous != null ? previous.name() : null)
                .commentReactionCount(fresh.getReactionCount())
                .build());

        return postMapper.toCommentResponse(fresh);
    }

    @Transactional
    public void removeCommentReaction(UUID commentId, UUID userId) {
        commentReactionRepository.findByCommentIdAndUserId(commentId, userId).ifPresent(r -> {
            PostReactionType previous = r.getReactionType();
            UUID postId = r.getComment().getPost().getId();
            commentReactionRepository.delete(r);
            commentRepository.updateReactionCount(commentId, -1);

            PostComment fresh = commentRepository.findById(commentId).orElse(null);
            User actor = userRepository.findById(userId).orElse(null);
            realtime.broadcast(PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.COMMENT_REACTION_REMOVED)
                    .postId(postId)
                    .actorId(userId)
                    .actorUsername(actor != null ? actor.getUsername() : null)
                    .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                    .commentId(commentId)
                    .previousReactionType(previous.name())
                    .commentReactionCount(fresh != null ? fresh.getReactionCount() : null)
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
        comment.setIsDeleted(true);
        comment.setDeletedAt(LocalDateTime.now());
        commentRepository.save(comment);
        postRepository.updateCommentCount(comment.getPost().getId(), -1);
        UUID parentId = null;
        Long parentReplyCount = null;
        if (comment.getParent() != null) {
            parentId = comment.getParent().getId();
            commentRepository.updateReplyCount(parentId, -1);
            parentReplyCount = commentRepository.findById(parentId)
                    .map(PostComment::getReplyCount).orElse(null);
        }

        Post fresh = postRepository.findById(postId).orElse(null);
        User actor = userRepository.findById(requesterId).orElse(null);
        realtime.broadcast(PostRealtimeEvent.builder()
                .eventType(PostRealtimeEventType.COMMENT_DELETED)
                .postId(postId)
                .actorId(requesterId)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorAvatarUrl(actor != null ? actor.getProfileImage() : null)
                .commentId(commentId)
                .parentCommentId(parentId)
                .postCommentCount(fresh != null ? fresh.getCommentCount() : null)
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