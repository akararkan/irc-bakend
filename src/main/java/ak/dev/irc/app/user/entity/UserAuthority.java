package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "user_authorities",
    indexes = @Index(name = "idx_authority_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAuthority extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_authority_user"))
    private User user;

    /**
     * Spring Security authority string.
     * Examples: ROLE_ADMIN, WRITE_PUBLICATION, REVIEW_SUBMISSION
     */
    @Column(name = "authority", nullable = false, length = 100)
    private String authority;
}
