package ak.dev.irc.app.post.service;

import ak.dev.irc.app.post.dto.CreateCommentRequest;
import ak.dev.irc.app.post.dto.EditCommentRequest;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.mapper.PostMapper;
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

    // ── Add comment / reply ───────────────────────────────────

    @Transactional
    public CommentResponse addComment(UUID postId, UUID authorId, CreateCommentRequest req) {
        Post post = postRepository.findById(postId)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException("Post not found"));

        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

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
                .findByPostIdAndParentIsNullAndIsDeletedFalseOrderByCreatedAtDesc(postId, pageable)
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
                .findByParentIdAndIsDeletedFalseOrderByCreatedAtAsc(commentId, pageable)
                .map(c -> postMapper.toCommentResponse(c));
    }

    // ── React to comment ──────────────────────────────────────

    @Transactional
    public CommentResponse reactToComment(UUID commentId, UUID userId, ReactToPostRequest req) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));

        commentReactionRepository.findByCommentIdAndUserId(commentId, userId)
                .ifPresentOrElse(existing -> {
                    existing.setReactionType(req.getReactionType());
                    commentReactionRepository.save(existing);
                }, () -> {
                    User user = userRepository.getReferenceById(userId);
                    PostCommentReaction reaction = PostCommentReaction.builder()
                            .id(new PostCommentReactionId(commentId, userId))
                            .comment(comment)
                            .user(user)
                            .reactionType(req.getReactionType())
                            .build();
                    commentReactionRepository.save(reaction);
                    commentRepository.updateReactionCount(commentId, 1);
                });

        eventPublisher.publishCommentReacted(PostCommentReactedEvent.builder()
                .commentId(commentId)
                .reactorId(userId)
                .commentAuthorId(comment.getAuthor().getId())
                .postId(comment.getPost().getId())
                .reactionType(req.getReactionType().name())
                .build());

        return postMapper.toCommentResponse(commentRepository.findById(commentId).orElseThrow());
    }

    @Transactional
    public void removeCommentReaction(UUID commentId, UUID userId) {
        commentReactionRepository.findByCommentIdAndUserId(commentId, userId).ifPresent(r -> {
            commentReactionRepository.delete(r);
            commentRepository.updateReactionCount(commentId, -1);
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

        comment.setTextContent(req.getTextContent().trim());
        comment.setEdited(true);
        comment.setEditedAt(LocalDateTime.now());
        commentRepository.save(comment);

        return postMapper.toCommentResponse(comment);
    }
}