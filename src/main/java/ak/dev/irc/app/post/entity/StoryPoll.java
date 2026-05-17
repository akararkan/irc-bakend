package ak.dev.irc.app.post.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "story_polls")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryPoll extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", unique = true,
                foreignKey = @ForeignKey(name = "fk_poll_story"))
    private Story story;

    @Column(name = "question", nullable = false, length = 200)
    private String question;

    @Column(name = "option_a", nullable = false, length = 100)
    private String optionA;

    @Column(name = "option_b", nullable = false, length = 100)
    private String optionB;

    @Column(name = "vote_a_count", nullable = false)
    @Builder.Default
    private int voteACount = 0;

    @Column(name = "vote_b_count", nullable = false)
    @Builder.Default
    private int voteBCount = 0;

    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<StoryPollVote> votes = new ArrayList<>();
}
