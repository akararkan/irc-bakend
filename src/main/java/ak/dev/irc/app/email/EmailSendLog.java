package ak.dev.irc.app.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The email send-ledger (notifications-email.md §4): one append-only row per
 * dispatch decision. Before this, a throttled or muted email left only a log
 * line — "why didn't I get the email?" was unanswerable, and the admin email
 * health strip had no data.
 */
@Entity
@Table(name = "email_send_log",
        indexes = {
                @Index(name = "idx_email_log_time", columnList = "created_at"),
                @Index(name = "idx_email_log_recipient", columnList = "recipient_id, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailSendLog {

    /** Dispatch decision — QUEUED means handed to the async sender. */
    public enum Outcome {QUEUED, THROTTLED, DISABLED, MUTED, NO_ADDRESS, SKIPPED, FAILED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "recipient_id")
    private UUID recipientId;

    @Column(name = "kind", length = 40)
    private String kind;

    @Column(name = "group_key", length = 160)
    private String groupKey;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
