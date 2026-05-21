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
        UserActivityType type = parseType(r.getActivityType());
        return UserActivityResponse.builder()
                .id(r.getActivityId())
                .activityType(type)
                .label(labelFor(type))
                .subtitle(subtitleFor(type))
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
                .research(r.getResearchId() == null ? null
                        : UserActivityResponse.ResearchSummary.builder().id(r.getResearchId()).build())
                .researchComment(r.getResearchCommentId() == null ? null
                        : UserActivityResponse.CommentSummary.builder().id(r.getResearchCommentId()).build())
                .createdAt(toLocal(r.getCreatedAt()))
                .timeAgo(TimeDisplayUtil.timeAgo(toLocal(r.getCreatedAt())))
                .formattedDate(TimeDisplayUtil.formattedDate(toLocal(r.getCreatedAt())))
                .build();
    }

    /** Same shape, sourced from the type-partitioned mirror table. */
    public UserActivityResponse toResponseByType(UserActivityByTypeEntity r) {
        UserActivityType type = parseType(r.getActivityType());
        return UserActivityResponse.builder()
                .id(r.getActivityId())
                .activityType(type)
                .label(labelFor(type))
                .subtitle(subtitleFor(type))
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
                .research(r.getResearchId() == null ? null
                        : UserActivityResponse.ResearchSummary.builder().id(r.getResearchId()).build())
                .researchComment(r.getResearchCommentId() == null ? null
                        : UserActivityResponse.CommentSummary.builder().id(r.getResearchCommentId()).build())
                .createdAt(toLocal(r.getCreatedAt()))
                .timeAgo(TimeDisplayUtil.timeAgo(toLocal(r.getCreatedAt())))
                .formattedDate(TimeDisplayUtil.formattedDate(toLocal(r.getCreatedAt())))
                .build();
    }

    /**
     * Human label per activity type. Never returns {@code null} for a known
     * type so the frontend never has to render "unknown". For unrecognised
     * types (future enum value the mapper hasn't been taught yet) returns
     * a generic {@code "Activity"} string — the {@link #parseType} warn log
     * surfaces the gap.
     */
    private static String labelFor(UserActivityType type) {
        if (type == null) return "Activity";
        return switch (type) {
            case POST_CREATED          -> "Published a post";
            case POST_REACTION         -> "Liked a post";
            case POST_COMMENT          -> "Commented on a post";
            case POST_COMMENT_REACTION -> "Liked a comment";
            case POST_SHARE            -> "Shared a post";
            case POST_SAVED            -> "Saved a post";
            case REEL_WATCH            -> "Watched a reel";

            case GLOBAL_SEARCH         -> "Searched";
            case HASHTAG_SEARCH        -> "Searched a hashtag";
            case MENTION_LOOKUP        -> "Looked up a user";
            case USER_MENTIONED        -> "Mentioned you";
            case PROFILE_VIEW          -> "Visited a profile";
            case FOLLOWED_USER         -> "Followed a user";

            case QNA_QUESTION_CREATED  -> "Asked a question";
            case QNA_QUESTION_SAVED    -> "Saved a question";
            case QNA_ANSWER_CREATED    -> "Answered a question";
            case QNA_REANSWER_CREATED  -> "Replied to an answer";
            case QNA_ANSWER_REACTION   -> "Liked an answer";
            case QNA_BEST_ANSWER_VOTE  -> "Marked a best answer";
            case QNA_ANSWER_FEEDBACK   -> "Gave answer feedback";

            case RESEARCH_PUBLISHED        -> "Published a research paper";
            case RESEARCH_SAVED            -> "Saved a research paper";
            case RESEARCH_REACTION         -> "Liked a research paper";
            case RESEARCH_COMMENT          -> "Commented on a research paper";
            case RESEARCH_COMMENT_REACTION -> "Liked a research comment";

            case STORY_VIEWED          -> "Watched a story";
            case STORY_REACTED         -> "Reacted to a story";
            case STORY_REPLIED         -> "Replied to a story";
            case STORY_POLL_VOTED      -> "Voted in a story poll";

            case SOUND_USED            -> "Used a sound";
        };
    }

    /** Slightly longer copy for card subtitles. */
    private static String subtitleFor(UserActivityType type) {
        if (type == null) return null;
        return switch (type) {
            case POST_SAVED           -> "Bookmarked for later";
            case RESEARCH_SAVED       -> "Bookmarked for later";
            case QNA_QUESTION_SAVED   -> "Bookmarked for later";
            case REEL_WATCH           -> "Reel view recorded";
            case PROFILE_VIEW         -> "Profile visit";
            case FOLLOWED_USER        -> "New follow";
            case USER_MENTIONED       -> "You were tagged";
            default                   -> null;
        };
    }

    private static UserActivityType parseType(String s) {
        if (s == null) return null;
        try {
            return UserActivityType.valueOf(s);
        } catch (IllegalArgumentException e) {
            // An unknown stored string would surface to the frontend as
            // {"activityType": null} → "unknown" toast. Warn loudly so we
            // can patch the enum / mapper before the next deploy.
            org.slf4j.LoggerFactory.getLogger(UserActivityMapper.class)
                    .warn("[ACTIVITY] unknown activity_type='{}' in storage — extend UserActivityType enum", s);
            return null;
        }
    }

    private static PostReactionType parseReaction(String s) {
        if (s == null) return null;
        try { return PostReactionType.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDateTime toLocal(java.time.Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
