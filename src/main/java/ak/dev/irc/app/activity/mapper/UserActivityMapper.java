package ak.dev.irc.app.activity.mapper;

import ak.dev.irc.app.activity.cassandra.entity.UserActivityByTypeEntity;
import ak.dev.irc.app.activity.cassandra.entity.UserActivityEntity;
import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.post.enums.PostReactionType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Cassandra-row → response mapper.
 *
 * <p>Rich joined data (author summary, post text preview, question title,
 * answer body) is intentionally omitted — those tables live in Cassandra
 * and the frontend hydrates each item via its dedicated endpoint
 * ({@code /api/v1/posts/{id}}, {@code /api/v1/questions/{id}}, …). The
 * response carries the id-shaped summaries so the client knows what to
 * fetch.</p>
 */
@Component
public class UserActivityMapper {

    public UserActivityResponse toResponse(UserActivityEntity r) {
        return UserActivityResponse.builder()
                .id(r.getActivityId())
                .activityType(parseType(r.getActivityType()))
                .reactionType(parseReaction(r.getReactionType()))
                .watchedSeconds(r.getWatchedSeconds())
                .post(r.getPostId() == null ? null
                        : UserActivityResponse.PostSummary.builder().id(r.getPostId()).build())
                .comment(r.getCommentId() == null ? null
                        : UserActivityResponse.CommentSummary.builder().id(r.getCommentId()).build())
                .query(r.getQuery())
                .searchScope(r.getSearchScope())
                .hitCount(r.getHitCount())
                .targetUser(r.getTargetUserId() == null ? null
                        : UserActivityResponse.AuthorSummary.builder().id(r.getTargetUserId()).build())
                .question(r.getQuestionId() == null ? null
                        : UserActivityResponse.QuestionSummary.builder().id(r.getQuestionId()).build())
                .answer(r.getAnswerId() == null ? null
                        : UserActivityResponse.AnswerSummary.builder().id(r.getAnswerId()).build())
                .qnaReactionType(r.getQnaReactionType())
                .createdAt(toLocal(r.getCreatedAt()))
                .timeAgo(TimeDisplayUtil.timeAgo(toLocal(r.getCreatedAt())))
                .formattedDate(TimeDisplayUtil.formattedDate(toLocal(r.getCreatedAt())))
                .build();
    }

    /** Same shape, sourced from the type-partitioned mirror table. */
    public UserActivityResponse toResponseByType(UserActivityByTypeEntity r) {
        return UserActivityResponse.builder()
                .id(r.getActivityId())
                .activityType(parseType(r.getActivityType()))
                .reactionType(parseReaction(r.getReactionType()))
                .watchedSeconds(r.getWatchedSeconds())
                .post(r.getPostId() == null ? null
                        : UserActivityResponse.PostSummary.builder().id(r.getPostId()).build())
                .comment(r.getCommentId() == null ? null
                        : UserActivityResponse.CommentSummary.builder().id(r.getCommentId()).build())
                .query(r.getQuery())
                .searchScope(r.getSearchScope())
                .hitCount(r.getHitCount())
                .targetUser(r.getTargetUserId() == null ? null
                        : UserActivityResponse.AuthorSummary.builder().id(r.getTargetUserId()).build())
                .question(r.getQuestionId() == null ? null
                        : UserActivityResponse.QuestionSummary.builder().id(r.getQuestionId()).build())
                .answer(r.getAnswerId() == null ? null
                        : UserActivityResponse.AnswerSummary.builder().id(r.getAnswerId()).build())
                .qnaReactionType(r.getQnaReactionType())
                .createdAt(toLocal(r.getCreatedAt()))
                .timeAgo(TimeDisplayUtil.timeAgo(toLocal(r.getCreatedAt())))
                .formattedDate(TimeDisplayUtil.formattedDate(toLocal(r.getCreatedAt())))
                .build();
    }

    private static UserActivityType parseType(String s) {
        if (s == null) return null;
        try { return UserActivityType.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static PostReactionType parseReaction(String s) {
        if (s == null) return null;
        try { return PostReactionType.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDateTime toLocal(java.time.Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
