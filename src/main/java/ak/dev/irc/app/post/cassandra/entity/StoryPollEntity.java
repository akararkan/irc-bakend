package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One poll per story. The story_id IS the partition key — that gives us
 * a free "get the poll attached to this story" point read. TTL'd 24h so
 * polls vanish with their story.
 */
@Table("poll_by_story")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryPollEntity {

    @PrimaryKey
    @Column("story_id")
    private UUID storyId;

    @Column("poll_id")    private UUID    pollId;
    @Column("question")   private String  question;
    @Column("option_a")   private String  optionA;
    @Column("option_b")   private String  optionB;
    @Column("author_id")  private UUID    authorId;
    @Column("created_at") private Instant createdAt;
}
