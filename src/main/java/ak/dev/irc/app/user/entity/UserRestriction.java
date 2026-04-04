package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_restrictions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_restrictions",
        columnNames = {"restrictor_id", "restricted_id"}
    ),
    indexes = {
        @Index(name = "idx_restrict_restrictor", columnList = "restrictor_id"),
        @Index(name = "idx_restrict_restricted",  columnList = "restricted_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRestriction extends BaseAuditEntity {

    @EmbeddedId
    private UserRestrictionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("restrictorId")
    @JoinColumn(name = "restrictor_id",
                foreignKey = @ForeignKey(name = "fk_restriction_restrictor"))
    private User restrictor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("restrictedId")
    @JoinColumn(name = "restricted_id",
                foreignKey = @ForeignKey(name = "fk_restriction_restricted"))
    private User restricted;

    @Column(name = "restricted_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime restrictedAt = LocalDateTime.now();
}
