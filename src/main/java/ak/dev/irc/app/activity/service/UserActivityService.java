package ak.dev.irc.app.activity.service;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.post.enums.PostReactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserActivityService {

    Page<UserActivityResponse> listMyActivity(UUID userId, UserActivityType filter, Pageable pageable);

    void deleteOne(UUID userId, UUID activityId);

    int deleteAll(UUID userId);

    void recordPostReaction(UUID userId, UUID postId, PostReactionType reactionType);

    void recordPostComment(UUID userId, UUID postId, UUID commentId);

    void recordPostCommentReaction(UUID userId, UUID postId, UUID commentId, PostReactionType reactionType);

    void recordPostShare(UUID userId, UUID postId);

    void recordReelWatch(UUID userId, UUID postId, Integer watchedSeconds);
}
