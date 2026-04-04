package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "research_saves",
    indexes = {
        @Index(name = "idx_rsave_research", columnList = "research_id"),
        @Index(name = "idx_rsave_user",     columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchSave extends BaseAuditEntity {

    @EmbeddedId
    private ResearchSaveId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("researchId")
    @JoinColumn(name = "research_id",
                foreignKey = @ForeignKey(name = "fk_rsave_research"))
    private Research research;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
                foreignKey = @ForeignKey(name = "fk_rsave_user"))
    private User user;

    /** Optional: user can organise saves into collections */
    @Column(name = "collection_name", length = 100)
    private String collectionName;
}
