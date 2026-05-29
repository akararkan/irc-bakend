package ak.dev.irc.app.post.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Reverse index: {@code poll_id → (story_id, author_id)}.
 *
 * <p>The canonical {@link StoryPollEntity} is keyed by {@code story_id} (one
 * poll per story). When a request only carries {@code pollId} — vote, results,
 * voter list — there is no way to walk back to the story (and therefore the
 * author) without scanning. This single-row lookup table closes that gap so
 * we can:</p>
 * <ul>
 *   <li>Push a realtime {@code POLL_VOTE_CAST} event to the author on every
 *       new vote (StoryEditor sees live counts without polling).</li>
 *   <li>Enforce author-only access on
 *       {@code GET /polls/{pollId}/voters/{choice}} — previously was
 *       authenticated-only because the auth check had no author to compare
 *       against.</li>
 * </ul>
 *
 * <p>Written once on poll create; never updated; deleted with the poll. Old
 * polls created before this index exists return {@code Optional.empty()} from
 * the lookup, in which case callers fall back to the pre-index behaviour
 * (no realtime push, no author-only restriction).</p>
 */
@Table("poll_by_id")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PollByIdEntity {

    @PrimaryKey
    @Column("poll_id")
    private UUID pollId;

    @Column("story_id")  private UUID storyId;
    @Column("author_id") private UUID authorId;
}
