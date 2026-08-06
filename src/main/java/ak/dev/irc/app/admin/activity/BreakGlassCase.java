package ak.dev.irc.app.admin.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * A break-glass authorization case (activity-engagement.md §3.1/§5): the
 * dual-control gate in front of per-user activity forensics. Opened by one
 * admin with a mandatory reason, approved by a DIFFERENT admin, time-boxed —
 * absent an OPEN case, the forensic reads 403. The default is no access.
 */
@Entity
@Table(name = "admin_breakglass_cases",
        indexes = @Index(name = "idx_breakglass_target", columnList = "target_user_id, status"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BreakGlassCase {

    public enum Status {PENDING_APPROVAL, OPEN, CLOSED, REJECTED}

    public enum CaseKind {LEGAL_HOLD, LAW_ENFORCEMENT, SAFETY_INVESTIGATION}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private CaseKind kind;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    /** External case/ticket reference (legal process id, report id, …). */
    @Column(name = "case_ref", length = 120)
    private String caseRef;

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING_APPROVAL;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** Cases auto-expire — forensic access is never open-ended. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    void onCreate() {
        if (openedAt == null) openedAt = LocalDateTime.now();
        if (expiresAt == null) expiresAt = openedAt.plusDays(7);
    }

    public boolean isOpenNow() {
        return status == Status.OPEN
                && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }
}
