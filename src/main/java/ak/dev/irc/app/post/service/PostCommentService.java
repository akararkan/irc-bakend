package ak.dev.irc.app.post.service;

import ak.dev.irc.app.post.dto.CreateCommentRequest;
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
            commentRepository.updateReplyCount(parent.getId(), 1);
        }

        PostComment comment = PostComment.builder()
                .post(post)
                .author(author)
                .parent(parent)
                .textContent(req.getTextContent())
                .voiceUrl(req.getVoiceUrl())
                .voiceDurationSeconds(req.getVoiceDurationSeconds())
                .voiceTranscript(req.getVoiceTranscript())
                .waveformData(req.getWaveformData())
                .build();

        comment = commentRepository.save(comment);
        postRepository.updateCommentCount(postId, 1);

        boolean isReply = parent != null;
        eventPublisher.publishPostCommented(PostCommentedEvent.builder()
                .postId(postId)
                .commentId(comment.getId())
                .commentAuthorId(authorId)
                .postAuthorId(post.getAuthor().getId())
                .parentCommentId(isReply ? parent.getId() : null)
                .isReply(isReply)
                .hasVoice(comment.getVoiceUrl() != null)
                .build());

        return postMapper.toCommentResponse(comment);
    }

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

    // ── Delete comment ────────────────────────────────────────

    @Transactional
    public void deleteComment(UUID commentId, UUID requesterId) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));
        if (!comment.getAuthor().getId().equals(requesterId)) {
            throw new AccessDeniedException("You can only delete your own comments");
        }
        comment.setIsDeleted(true);
        comment.setTextContent(null);
        comment.setVoiceUrl(null);
        commentRepository.save(comment);
        postRepository.updateCommentCount(comment.getPost().getId(), -1);
    }
}