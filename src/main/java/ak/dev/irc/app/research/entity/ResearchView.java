package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "research_views",
    indexes = {
        @Index(name = "idx_rview_research", columnList = "research_id"),
        @Index(name = "idx_rview_user",     columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchView extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rview_research"))
    private Research research;

    /** Nullable — anonymous views are tracked by IP only */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",
                foreignKey = @ForeignKey(name = "fk_rview_user"))
    private User user;

    /** IP address for deduplication of anonymous views */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** User-Agent for basic analytics */
    @Column(name = "user_agent", length = 500)
    private String userAgent;
}
