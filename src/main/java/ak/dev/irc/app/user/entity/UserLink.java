package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.LinkPlatform;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "user_links",
    indexes = @Index(name = "idx_link_profile", columnList = "profile_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLink extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_link_profile"))
    private UserProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 30)
    private LinkPlatform platform;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = true;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
