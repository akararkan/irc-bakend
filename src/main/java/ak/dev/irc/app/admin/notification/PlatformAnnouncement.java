package ak.dev.irc.app.admin.notification;

import ak.dev.irc.app.user.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A platform announcement run (notifications-email.md §4) — the send history
 * behind the composer, with reach accounting. The rows are the audit trail of
 * the single most abusable tool in the dashboard.
 */
@Entity
@Table(name = "platform_announcements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAnnouncement {

    public enum Status {SCHEDULED, SENDING, SENT, FAILED, CANCELLED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** Audience narrowing: null = every active user. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_role", length = 20)
    private Role audienceRole;

    /** Audience narrowing: only users active within the last N days. */
    @Column(name = "active_since_days")
    private Integer activeSinceDays;

    /** Audience narrowing: only users whose preferredLanguage matches. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_language", length = 8)
    private ak.dev.irc.app.common.enums.Language audienceLanguage;

    /** Non-null → held as SCHEDULED until the minute sweep fires it. */
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.SENDING;

    @Column(name = "targeted_count")
    private long targetedCount;

    @Column(name = "delivered_count")
    private long deliveredCount;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
