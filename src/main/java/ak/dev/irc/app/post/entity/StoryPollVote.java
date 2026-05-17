package ak.dev.irc.app.post.entity;

import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "story_poll_votes",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_poll_voter",
        columnNames = {"poll_id", "voter_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryPollVote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", foreignKey = @ForeignKey(name = "fk_vote_poll"))
    private StoryPoll poll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voter_id", foreignKey = @ForeignKey(name = "fk_vote_voter"))
    private User voter;

    /** "A" or "B" */
    @Column(name = "choice", nullable = false, length = 1)
    private String choice;

    @CreationTimestamp
    @Column(name = "voted_at", updatable = false)
    private LocalDateTime votedAt;
}
