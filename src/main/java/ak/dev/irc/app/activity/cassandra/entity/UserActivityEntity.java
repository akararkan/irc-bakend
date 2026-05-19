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
 * Per-user engagement history feed — Cassandra primary view.
 * Partition by user_id, clustered newest first so "my history" is a
 * single partition scan.
 *
 * For the per-type filter view ("just my reactions") see
 * {@link UserActivityByTypeEntity} — the same row is denormalized into
 * both tables on write.
 */
@Table("activity_by_user")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserActivityEntity {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private UUID userId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "activity_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID activityId;

    @Column("activity_type")       private String activityType;

    // Post-side context
    @Column("post_id")             private UUID   postId;
    @Column("comment_id")          private UUID   commentId;
    @Column("reaction_type")       private String reactionType;
    @Column("watched_seconds")     private Integer watchedSeconds;

    // Search / mention / profile context
    @Column("query")               private String  query;
    @Column("search_scope")        private String  searchScope;
    @Column("hit_count")           private Integer hitCount;
    @Column("target_user_id")      private UUID    targetUserId;

    // Q&A context
    @Column("question_id")         private UUID    questionId;
    @Column("answer_id")           private UUID    answerId;
    @Column("qna_reaction_type")   private String  qnaReactionType;

    // Research context
    @Column("research_id")         private UUID    researchId;
    @Column("research_comment_id") private UUID    researchCommentId;
}
