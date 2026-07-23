package ak.dev.irc.app.chat.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * Discussion-group comment index — a channel post's comments are the linked
 * group's replies to it. One partition per post, newest comment first, so the
 * comment thread is a single-partition slice without scanning the group log.
 * ({@code chat_}-prefixed: the social-post domain owns {@code comments_by_post}.)
 */
@Table("chat_comments_by_post")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatCommentByPostEntity {

    @PrimaryKeyColumn(name = "post_message_id", type = PrimaryKeyType.PARTITIONED)
    private Long postMessageId;

    @PrimaryKeyColumn(name = "comment_message_id", type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Long commentMessageId;

    @Column("created_at")
    private Instant createdAt;
}
