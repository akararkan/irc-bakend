package ak.dev.irc.app.activity.service;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserActivityService {

    Page<UserActivityResponse> listMyActivity(UUID userId, UserActivityType filter, Pageable pageable);

    void deleteOne(UUID userId, UUID activityId);

    int deleteAll(UUID userId, UserActivityType filter);

    void recordPostReaction(UUID userId, UUID postId, PostReactionType reactionType);

    void recordPostComment(UUID userId, UUID postId, UUID commentId);

    void recordPostCommentReaction(UUID userId, UUID postId, UUID commentId, PostReactionType reactionType);

    void recordPostShare(UUID userId, UUID postId);

    void recordReelWatch(UUID userId, UUID postId, Integer watchedSeconds);

    /** Record a global search query (multi-corpus). Async, fire-and-forget. */
    void recordGlobalSearch(UUID userId, String query, String searchScope, int hitCount);

    /** Record a hashtag-only search. Async, fire-and-forget. */
    void recordHashtagSearch(UUID userId, String tag, int hitCount);

    /** Record a mention-picker lookup. {@code targetUserId} is null when only typing a fragment. */
    void recordMentionLookup(UUID userId, String query, UUID targetUserId, int hitCount);

    /** Record a viewer opening another user's profile. */
    void recordProfileView(UUID viewerId, UUID profileUserId);

    // ── QnA ──────────────────────────────────────────────────────────────

    void recordQnaQuestionCreated(UUID userId, UUID questionId);

    /** Record a top-level answer or a reanswer. {@code parentAnswerId} != null means reanswer. */
    void recordQnaAnswerCreated(UUID userId, UUID questionId, UUID answerId, UUID parentAnswerId);

    void recordQnaAnswerReaction(UUID userId, UUID questionId, UUID answerId, AnswerReactionType reactionType);

    /** Record a scholar marking / unmarking an answer as a best answer. */
    void recordQnaBestAnswerVote(UUID voterId, UUID questionId, UUID answerId, boolean voted);

    void recordQnaAnswerFeedback(UUID userId, UUID questionId, UUID answerId);

    // ── Research ─────────────────────────────────────────────────────────

    void recordResearchReaction(UUID userId, UUID researchId);

    void recordResearchComment(UUID userId, UUID researchId, UUID commentId);

    void recordResearchCommentReaction(UUID userId, UUID researchId, UUID commentId);
}
