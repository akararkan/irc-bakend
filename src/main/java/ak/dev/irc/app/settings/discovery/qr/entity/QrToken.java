package ak.dev.irc.app.settings.discovery.qr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A rotatable, opaque QR-discovery token (spec §6). Discovery via QR resolves a
 * signed/opaque token rather than a raw user id: {@code irc://u/{opaque}}. When a
 * user regenerates their QR the old opaque value is replaced, so previously
 * printed codes stop working — that is the point.
 *
 * <p>One active token per user (unique {@code user_id}).</p>
 */
@Entity
@Table(name = "qr_tokens",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_qr_token_user",  columnNames = "user_id"),
           @UniqueConstraint(name = "uq_qr_token_opaque", columnNames = "opaque_token")
       },
       indexes = @Index(name = "idx_qr_token_opaque", columnList = "opaque_token"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Opaque, unguessable, URL-safe token embedded in the QR code. */
    @Column(name = "opaque_token", nullable = false, length = 64)
    private String opaqueToken;

    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (rotatedAt == null) rotatedAt = createdAt;
    }
}
