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
import ak.dev.irc.app.post.service.PostCommentService;
import ak.dev.irc.app.post.service.PostService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final PostService postService;
    private final PostCommentService postCommentService;

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
        cascadeUndo(activity);
        activityRepo.delete(activity);
    }

    @Override
    @Transactional
    public int deleteAll(UUID userId, UserActivityType filter) {
        List<UserActivity> activities = (filter == null)
                ? activityRepo.findAllByUserId(userId)
                : activityRepo.findAllByUserIdAndActivityType(userId, filter);
        for (UserActivity a : activities) {
            cascadeUndo(a);
        }
        activityRepo.deleteAll(activities);
        return activities.size();
    }

    /**
     * Undo the underlying user action when an activity row is removed.
     * REEL_WATCH is intentionally a no-op so the granular ReelView history is preserved.
     * Best-effort: a failure in the cascade does not block activity-row deletion.
     */
    private void cascadeUndo(UserActivity a) {
        UUID userId = a.getUser().getId();
        Post post = a.getPost();
        PostComment comment = a.getComment();
        try {
            switch (a.getActivityType()) {
                case POST_REACTION -> {
                    if (post != null) postService.removeReaction(post.getId(), userId);
                }
                case POST_COMMENT -> {
                    if (post != null && comment != null && !comment.isDeleted()) {
                        postCommentService.deleteComment(post.getId(), comment.getId(), userId);
                    }
                }
                case POST_COMMENT_REACTION -> {
                    if (comment != null) postCommentService.removeCommentReaction(comment.getId(), userId);
                }
                case POST_SHARE -> {
                    if (post != null) postService.undoRepost(post.getId(), userId);
                }
                case REEL_WATCH -> {
                    // intentionally no-op — ReelView is preserved
                }
            }
        } catch (Exception e) {
            log.warn("[ACTIVITY] cascade undo skipped for activity={} type={}: {}",
                    a.getId(), a.getActivityType(), e.getMessage());
        }
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
    public void recordPostShare(UUID userId, UUID postId) {
        User user = userRepo.findActiveById(userId).orElse(null);
        Post post = postRepo.findById(postId).orElse(null);
        if (user == null || post == null) {
            log.warn("[ACTIVITY] recordPostShare skipped — user/post not found (userId={}, postId={})", userId, postId);
            return;
        }
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.POST_SHARE)
                .post(post)
                .build();
        activityRepo.save(activity);
    }

    @Override
    @Transactional
    public void recordReelWatch(UUID userId, UUID postId, Integer watchedSeconds) {
        User user = userRepo.findActiveById(userId).orElse(null);
        Post post = postRepo.findById(postId).orElse(null);
        if (user == null || post == null) {
            log.warn("[ACTIVITY] recordReelWatch skipped — user/post not found (userId={}, postId={})", userId, postId);
            return;
        }
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.REEL_WATCH)
                .post(post)
                .watchedSeconds(watchedSeconds)
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
