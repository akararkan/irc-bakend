package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.enums.LinkPlatform;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "user_links",
    indexes = @Index(name = "idx_link_user", columnList = "user_id")
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
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_link_user"))
    private User user;

    /**
     * Platform type: FACEBOOK, TWITTER, ORCID, etc.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 30)
    private LinkPlatform platform;

    /**
     * User-written label/description.
     * Example: "My Facebook Page" or "ORCID Research Profile"
     */
    @Column(name = "description", nullable = false, length = 200)
    private String description;

    /**
     * The actual URL.
     * Example: https://www.facebook.com/myprofile
     */
    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = true;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
