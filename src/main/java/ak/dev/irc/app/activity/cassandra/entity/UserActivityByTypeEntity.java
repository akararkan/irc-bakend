package ak.dev.irc.app.activity.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Same data as {@link UserActivityEntity} but partitioned by
 * (user_id, activity_type) so the type-filter view is also one
 * partition scan. Written on every activity insert.
 */
@Table("activity_by_user_and_type")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserActivityByTypeEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private UUID userId;

    @PrimaryKeyColumn(name = "activity_type", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private String activityType;

    @PrimaryKeyColumn(name = "created_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "activity_id", ordinal = 3, type = PrimaryKeyType.CLUSTERED)
    private UUID activityId;

    @Column("post_id")             private UUID    postId;
    @Column("comment_id")          private UUID    commentId;
    @Column("reaction_type")       private String  reactionType;
    @Column("watched_seconds")     private Integer watchedSeconds;
    @Column("query")               private String  query;
    @Column("search_scope")        private String  searchScope;
    @Column("hit_count")           private Integer hitCount;
    @Column("target_user_id")      private UUID    targetUserId;
    @Column("question_id")         private UUID    questionId;
    @Column("answer_id")           private UUID    answerId;
    @Column("qna_reaction_type")   private String  qnaReactionType;
    @Column("research_id")         private UUID    researchId;
    @Column("research_comment_id") private UUID    researchCommentId;
}
