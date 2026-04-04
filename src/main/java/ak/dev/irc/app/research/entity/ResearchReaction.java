package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "research_reactions",
    indexes = {
        @Index(name = "idx_rreaction_research", columnList = "research_id"),
        @Index(name = "idx_rreaction_user",     columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchReaction extends BaseAuditEntity {

    @EmbeddedId
    private ResearchReactionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("researchId")
    @JoinColumn(name = "research_id",
                foreignKey = @ForeignKey(name = "fk_rreaction_research"))
    private Research research;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
                foreignKey = @ForeignKey(name = "fk_rreaction_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 20)
    private ReactionType reactionType;
}
