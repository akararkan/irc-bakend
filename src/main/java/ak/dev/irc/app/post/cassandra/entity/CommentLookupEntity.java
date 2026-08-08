package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Point-read by comment_id. Sister of posts_by_id but for comments.
 *
 * Why this exists: comments_by_post is keyed by (post_id, created_at, comment_id)
 * and replies_by_comment by (parent_id, created_at, reply_id). Given only a
 * comment_id (which is all the URL gives us), Cassandra has no way to find the
 * row without scanning. This table closes that gap.
 *
 * Used by:
 *   • edit / soft-delete paths — need the partition key (post_id or parent_id)
 *     and the cluster key (created_at) to reach the existing row.
 *   • reply-to-a-reply flow — to walk up to the top-level parent so the new
 *     reply lands as a sibling (project rule: replies are flat at depth 1).
 */
@Table("comment_lookup")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommentLookupEntity {

    @PrimaryKey
    @Column("comment_id")
    private UUID commentId;

    @Column("post_id")    private UUID    postId;
    @Column("parent_id")  private UUID    parentId;     // null = top-level
    @Column("author_id")  private UUID    authorId;
    @Column("created_at") private Instant createdAt;
    @Column("is_reply")   private Boolean reply;

    /**
     * Mirror of the comment/reply row's moderation state. Kept here because this
     * is the only table keyed by comment id alone — the applier that publishes a
     * held reply later has nothing else to resolve it through.
     */
    @Column("moderation_status") private String moderationStatus;
}
