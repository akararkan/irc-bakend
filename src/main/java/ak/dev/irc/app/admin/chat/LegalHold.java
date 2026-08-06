package ak.dev.irc.app.admin.chat;

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
 * A chat legal hold (chat-oversight.md §8): the ONLY sanctioned path to
 * message content, dual-controlled — the admin who opens a hold can never be
 * the one who approves it, and content is released only on execute, bounded
 * to the newest 500 messages of one conversation. Every transition audits as
 * {@code LEGAL_HOLD_*}; the row itself is the case file.
 */
@Entity
@Table(name = "legal_holds",
        indexes = @Index(name = "idx_legal_hold_status", columnList = "status, opened_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LegalHold {

    public enum Status {OPEN, APPROVED, EXECUTED, REJECTED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** Case reference — ticket / court order / DSA request id. Required. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "executed_by")
    private UUID executedBy;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** How many messages the execute read returned. */
    @Column(name = "message_count")
    private Integer messageCount;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @PrePersist
    void onCreate() {
        if (openedAt == null) openedAt = LocalDateTime.now();
    }
}
