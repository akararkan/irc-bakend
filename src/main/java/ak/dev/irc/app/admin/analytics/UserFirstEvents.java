package ak.dev.irc.app.admin.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Activation-funnel milestones per user (analytics-kpis.md §4.3/§6.4
 * {@code user_first_events}): written incrementally on-event, each column set
 * exactly once. Powers registered → active → profile → follow → content.
 */
@Entity
@Table(name = "user_first_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFirstEvents {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "first_seen")
    private LocalDateTime firstSeen;

    @Column(name = "profile_completed_at")
    private LocalDateTime profileCompletedAt;

    @Column(name = "first_follow_at")
    private LocalDateTime firstFollowAt;

    @Column(name = "first_content_at")
    private LocalDateTime firstContentAt;
}
