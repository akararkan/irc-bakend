package ak.dev.irc.app.activity.service.impl;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.entity.UserActivity;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.activity.mapper.UserActivityMapper;
import ak.dev.irc.app.activity.repository.UserActivityRepository;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostComment;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.post.repository.PostCommentRepository;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityServiceImpl implements UserActivityService {

    private final UserActivityRepository activityRepo;
    private final UserRepository userRepo;
    private final PostRepository postRepo;
    private final PostCommentRepository commentRepo;
    private final UserActivityMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Page<UserActivityResponse> listMyActivity(UUID userId, UserActivityType filter, Pageable pageable) {
        Page<UserActivity> page = (filter == null)
                ? activityRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                : activityRepo.findByUserIdAndActivityTypeOrderByCreatedAtDesc(userId, filter, pageable);
        return page.map(mapper::toResponse);
    }

    @Override
    @Transactional
    public void deleteOne(UUID userId, UUID activityId) {
        UserActivity activity = activityRepo.findById(activityId)
                .orElseThrow(() -> new ResourceNotFoundException("UserActivity", "id", activityId));
        if (!activity.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You cannot delete another user's activity");
        }
        activityRepo.delete(activity);
    }

    @Override
    @Transactional
    public int deleteAll(UUID userId) {
        return activityRepo.deleteAllByUserId(userId);
    }

    @Override
    @Transactional
    public void recordPostReaction(UUID userId, UUID postId, PostReactionType reactionType) {
        User user = userRepo.findActiveById(userId).orElse(null);
        Post post = postRepo.findById(postId).orElse(null);
        if (user == null || post == null) {
            log.warn("[ACTIVITY] recordPostReaction skipped — user/post not found (userId={}, postId={})", userId, postId);
            return;
        }
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.POST_REACTION)
                .post(post)
                .reactionType(reactionType)
                .build();
        activityRepo.save(activity);
    }

    @Override
    @Transactional
    public void recordPostComment(UUID userId, UUID postId, UUID commentId) {
        User user = userRepo.findActiveById(userId).orElse(null);
        Post post = postRepo.findById(postId).orElse(null);
        PostComment comment = commentRepo.findById(commentId).orElse(null);
        if (user == null || post == null || comment == null) {
            log.warn("[ACTIVITY] recordPostComment skipped — user/post/comment not found");
            return;
        }
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.POST_COMMENT)
                .post(post)
                .comment(comment)
                .build();
        activityRepo.save(activity);
    }

    @Override
    @Transactional
    public void recordPostCommentReaction(UUID userId, UUID postId, UUID commentId, PostReactionType reactionType) {
        User user = userRepo.findActiveById(userId).orElse(null);
        Post post = postRepo.findById(postId).orElse(null);
        PostComment comment = commentRepo.findById(commentId).orElse(null);
        if (user == null || post == null || comment == null) {
            log.warn("[ACTIVITY] recordPostCommentReaction skipped — user/post/comment not found");
            return;
        }
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.POST_COMMENT_REACTION)
                .post(post)
                .comment(comment)
                .reactionType(reactionType)
                .build();
        activityRepo.save(activity);
    }
}
