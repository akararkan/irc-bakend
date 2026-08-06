package ak.dev.irc.app.admin.ops;

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
 * The DLQ parking lot (operations.md §5.2): before this, the dead-letter
 * drain ACK'd and discarded every poison message after one ERROR log line —
 * the payload was unrecoverable. Now each dead letter parks here for browse /
 * requeue / discard.
 */
@Entity
@Table(name = "dead_letters",
        indexes = @Index(name = "idx_dead_letter_status", columnList = "status, received_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetter {

    public enum Status {PARKED, REQUEUED, DISCARDED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "original_exchange", length = 120)
    private String originalExchange;

    @Column(name = "original_queue", length = 120)
    private String originalQueue;

    @Column(name = "routing_key", length = 160)
    private String routingKey;

    /** Jackson {@code __TypeId__} header — the event class. */
    @Column(name = "type_id", length = 200)
    private String typeId;

    /** Raw payload, base64-encoded (JSON bodies stay readable after decode). */
    @Column(name = "payload_b64", columnDefinition = "text")
    private String payloadB64;

    @Column(name = "headers_json", columnDefinition = "text")
    private String headersJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private Status status = Status.PARKED;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = LocalDateTime.now();
    }
}
