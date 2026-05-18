package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.research.enums.ContributorRole;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A named participant on a research publication other than the corresponding
 * (owning) researcher.
 *
 * <p>Each row links a {@link Research} to a {@link User} with a
 * {@link ContributorRole}. Only the owning researcher may add or remove
 * contributors; the contributor list is unique on
 * {@code (research_id, user_id)}.</p>
 */
@Entity
@Table(
    name = "research_contributors",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rcontrib_research_user",
        columnNames = { "research_id", "user_id" }
    ),
    indexes = {
        @Index(name = "idx_rcontrib_research", columnList = "research_id"),
        @Index(name = "idx_rcontrib_user",     columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchContributor extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rcontrib_research"))
    private Research research;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rcontrib_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    @Builder.Default
    private ContributorRole role = ContributorRole.CO_AUTHOR;

    /** Position in the author/contributor list — lower = listed earlier. */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** Optional free-text describing the specific contribution. */
    @Column(name = "contribution_note", length = 500)
    private String contributionNote;
}
