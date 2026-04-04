package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "research_tags",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_research_tag",
        columnNames = {"research_id", "tag_name"}
    ),
    indexes = {
        @Index(name = "idx_rtag_research", columnList = "research_id"),
        @Index(name = "idx_rtag_name",     columnList = "tag_name")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchTag extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rtag_research"))
    private Research research;

    /** Normalised tag name (lowercase, trimmed) */
    @Column(name = "tag_name", nullable = false, length = 100)
    private String tagName;
}
