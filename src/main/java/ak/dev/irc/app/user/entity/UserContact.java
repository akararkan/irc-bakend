package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.ContactPlatform;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "user_contacts",
    indexes = @Index(name = "idx_contact_profile", columnList = "profile_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContact extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_contact_profile"))
    private UserProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private ContactPlatform platform;

    @Column(name = "value", nullable = false, length = 200)
    private String value;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = false;
}
