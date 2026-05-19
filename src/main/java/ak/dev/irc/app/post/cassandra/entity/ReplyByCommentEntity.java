package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Replies to a single comment. Per project rule, replies are flat at depth 1 —
 * a "reply to a reply" lands as a sibling of the existing reply, not deeper.
 */
@Table("replies_by_comment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReplyByCommentEntity {

    @PrimaryKeyColumn(name = "parent_id", type = PrimaryKeyType.PARTITIONED)
    private UUID parentId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.ASCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "reply_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID replyId;

    @Column("author_id")    private UUID   authorId;
    @Column("post_id")      private UUID   postId;
    @Column("text_content") private String textContent;
    @Column("media_url")    private String mediaUrl;
    @Column("is_deleted")   private Boolean deleted;
    @Column("is_edited")    private Boolean edited;
}
