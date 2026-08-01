package ak.dev.irc.app.settings.data.entity;

import ak.dev.irc.app.settings.data.enums.DeletionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A pending account deletion (spec §16). On request the account is made
 * invisible immediately (the owning user's {@code deletedAt} is set and all
 * sessions are revoked); this row only tracks the 30-day grace window, which
 * exists purely for recovery. The scheduled purge acts on rows whose
 * {@code purgeAfter} has passed.
 */
@Entity
@Table(name = "account_deletion_requests",
       indexes = @Index(name = "idx_deletion_user_status", columnList = "user_id, status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDeletionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private DeletionStatus status = DeletionStatus.PENDING_DELETION;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "purge_after", nullable = false)
    private LocalDateTime purgeAfter;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void onCreate() {
        if (requestedAt == null) requestedAt = LocalDateTime.now();
        if (purgeAfter == null) purgeAfter = requestedAt.plusDays(30);
    }
}
