package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "research_downloads",
    indexes = {
        @Index(name = "idx_rdownload_research", columnList = "research_id"),
        @Index(name = "idx_rdownload_user",     columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchDownload extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rdownload_research"))
    private Research research;

    /** Which specific media file was downloaded (nullable = whole research bundle) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id",
                foreignKey = @ForeignKey(name = "fk_rdownload_media"))
    private ResearchMedia media;

    /** Nullable for anonymous downloads */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
                foreignKey = @ForeignKey(name = "fk_rdownload_user"))
    private User user;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
