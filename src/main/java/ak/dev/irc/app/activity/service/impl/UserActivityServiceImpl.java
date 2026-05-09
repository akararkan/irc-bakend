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
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeBroadcaster;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityServiceImpl implements UserActivityService {

    private static final int QUERY_MAX_LEN = 200;

    private final UserActivityRepository activityRepo;
    private final UserRepository userRepo;
    private final PostRepository postRepo;
    private final PostCommentRepository commentRepo;
    private final UserActivityMapper mapper;
    private final PostService postService;
    private final PostCommentService postCommentService;
    private final UserActivityRealtimeBroadcaster realtimeBroadcaster;
    private final QuestionRepository questionRepo;
    private final QuestionAnswerRepository answerRepo;

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
                case GLOBAL_SEARCH, HASHTAG_SEARCH, MENTION_LOOKUP, PROFILE_VIEW,
                     QNA_QUESTION_CREATED, QNA_ANSWER_CREATED, QNA_REANSWER_CREATED,
                     QNA_ANSWER_REACTION, QNA_BEST_ANSWER_VOTE, QNA_ANSWER_FEEDBACK -> {
                    // history-only activities — nothing to undo
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
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
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
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
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
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
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
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
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
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
    }

    // ── Search / mention / profile activity ──────────────────────────────

    @Override
    @Async
    @Transactional
    public void recordGlobalSearch(UUID userId, String query, String searchScope, int hitCount) {
        if (userId == null || query == null || query.isBlank()) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        if (user == null) return;
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.GLOBAL_SEARCH)
                .query(truncate(query))
                .searchScope(truncate(searchScope, 120))
                .hitCount(hitCount)
                .build();
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordHashtagSearch(UUID userId, String tag, int hitCount) {
        if (userId == null || tag == null || tag.isBlank()) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        if (user == null) return;
        String normalized = tag.startsWith("#") ? tag : "#" + tag;
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.HASHTAG_SEARCH)
                .query(truncate(normalized))
                .hitCount(hitCount)
                .build();
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordMentionLookup(UUID userId, String query, UUID targetUserId, int hitCount) {
        if (userId == null) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        if (user == null) return;
        User target = (targetUserId == null) ? null : userRepo.findActiveById(targetUserId).orElse(null);
        UserActivity activity = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.MENTION_LOOKUP)
                .query(truncate(query))
                .targetUser(target)
                .hitCount(hitCount)
                .build();
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordProfileView(UUID viewerId, UUID profileUserId) {
        if (viewerId == null || profileUserId == null || viewerId.equals(profileUserId)) return;
        User viewer = userRepo.findActiveById(viewerId).orElse(null);
        User target = userRepo.findActiveById(profileUserId).orElse(null);
        if (viewer == null || target == null) return;
        UserActivity activity = UserActivity.builder()
                .user(viewer)
                .activityType(UserActivityType.PROFILE_VIEW)
                .targetUser(target)
                .build();
        UserActivity saved = activityRepo.save(activity);
        broadcast(saved);
    }

    // ── QnA activity ─────────────────────────────────────────────────────

    @Override
    @Async
    @Transactional
    public void recordQnaQuestionCreated(UUID userId, UUID questionId) {
        if (userId == null || questionId == null) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        Question question = questionRepo.findById(questionId).orElse(null);
        if (user == null || question == null) return;
        UserActivity saved = activityRepo.save(UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.QNA_QUESTION_CREATED)
                .question(question)
                .build());
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordQnaAnswerCreated(UUID userId, UUID questionId, UUID answerId, UUID parentAnswerId) {
        if (userId == null || answerId == null) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        Question question = (questionId != null) ? questionRepo.findById(questionId).orElse(null) : null;
        QuestionAnswer answer = answerRepo.findById(answerId).orElse(null);
        if (user == null || answer == null) return;
        UserActivity saved = activityRepo.save(UserActivity.builder()
                .user(user)
                .activityType(parentAnswerId != null
                        ? UserActivityType.QNA_REANSWER_CREATED
                        : UserActivityType.QNA_ANSWER_CREATED)
                .question(question)
                .answer(answer)
                .build());
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordQnaAnswerReaction(UUID userId, UUID questionId, UUID answerId,
                                        AnswerReactionType reactionType) {
        if (userId == null || answerId == null) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        Question question = (questionId != null) ? questionRepo.findById(questionId).orElse(null) : null;
        QuestionAnswer answer = answerRepo.findById(answerId).orElse(null);
        if (user == null || answer == null) return;
        UserActivity saved = activityRepo.save(UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.QNA_ANSWER_REACTION)
                .question(question)
                .answer(answer)
                .qnaReactionType(reactionType)
                .build());
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordQnaBestAnswerVote(UUID voterId, UUID questionId, UUID answerId, boolean voted) {
        if (voterId == null || answerId == null) return;
        User user = userRepo.findActiveById(voterId).orElse(null);
        Question question = (questionId != null) ? questionRepo.findById(questionId).orElse(null) : null;
        QuestionAnswer answer = answerRepo.findById(answerId).orElse(null);
        if (user == null || answer == null) return;
        UserActivity row = UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.QNA_BEST_ANSWER_VOTE)
                .question(question)
                .answer(answer)
                .build();
        row.audit(ak.dev.irc.app.common.enums.AuditAction.CREATE, voted ? "voted" : "unvoted");
        UserActivity saved = activityRepo.save(row);
        broadcast(saved);
    }

    @Override
    @Async
    @Transactional
    public void recordQnaAnswerFeedback(UUID userId, UUID questionId, UUID answerId) {
        if (userId == null || answerId == null) return;
        User user = userRepo.findActiveById(userId).orElse(null);
        Question question = (questionId != null) ? questionRepo.findById(questionId).orElse(null) : null;
        QuestionAnswer answer = answerRepo.findById(answerId).orElse(null);
        if (user == null || answer == null) return;
        UserActivity saved = activityRepo.save(UserActivity.builder()
                .user(user)
                .activityType(UserActivityType.QNA_ANSWER_FEEDBACK)
                .question(question)
                .answer(answer)
                .build());
        broadcast(saved);
    }

    private void broadcast(UserActivity activity) {
        if (activity == null) return;
        try {
            UserActivityResponse payload = mapper.toResponse(activity);
            realtimeBroadcaster.broadcast(activity.getUser().getId(),
                    UserActivityRealtimeEvent.from(payload));
        } catch (Exception ex) {
            log.debug("[ACTIVITY-RT] broadcast skipped: {}", ex.getMessage());
        }
    }

    private static String truncate(String text) {
        return truncate(text, QUERY_MAX_LEN);
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
