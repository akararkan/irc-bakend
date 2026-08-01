package ak.dev.irc.app.settings.data.entity;

import ak.dev.irc.app.settings.data.enums.ExportStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One personal-data export request (spec §16). Async by necessity — a full
 * export can be gigabytes — so the row tracks status while a worker streams the
 * archive to a file.
 */
@Entity
@Table(name = "export_jobs",
       indexes = @Index(name = "idx_export_job_user", columnList = "user_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ExportStatus status = ExportStatus.PENDING;

    /** Absolute path of the generated ZIP on the worker host (temp storage). */
    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
