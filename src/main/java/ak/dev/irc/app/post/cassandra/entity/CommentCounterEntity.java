package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

@Table("comment_counters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommentCounterEntity {

    @PrimaryKey
    @Column("comment_id")
    private UUID commentId;

    @Column("reaction_count") private Long reactionCount;
    @Column("reply_count")    private Long replyCount;
}
