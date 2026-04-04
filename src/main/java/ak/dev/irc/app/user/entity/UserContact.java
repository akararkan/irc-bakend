package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.ContactPlatform;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "user_contacts",
    indexes = @Index(name = "idx_contact_user", columnList = "user_id")
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
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_contact_user"))
    private User user;

    /**
     * Platform: TELEGRAM, WHATSAPP, EMAIL, PHONE, etc.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private ContactPlatform platform;

    /**
     * The actual contact value:
     * - Phone: +9647501234567
     * - Telegram: @myhandle
     * - Email: contact@example.com
     */
    @Column(name = "value", nullable = false, length = 200)
    private String value;

    /** True = visible on public profile */
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = false;
}
