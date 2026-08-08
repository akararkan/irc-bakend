package ak.dev.irc.app.moderation.entity;

import ak.dev.irc.app.moderation.enums.ModelVersionStatus;
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
 * One trained artifact in the model registry — {@code model_versions} in
 * MODERATION_ROADMAP.md §9/§12.4.
 *
 * <p>Spring Boot owns this table; the training container only writes weights to
 * the shared volume and reports metrics back. Rollback therefore never depends
 * on re-running training — every artifact stays on disk and any {@code RETIRED}
 * row can be re-promoted (§12.4).</p>
 */
@Entity
@Table(name = "moderation_model_versions",
        indexes = {
                @Index(name = "idx_mod_version_status", columnList = "status"),
                @Index(name = "idx_mod_version_trained", columnList = "trained_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** {@code v1}, {@code v2}, … — the artifact directory name in the shared volume. */
    @Column(name = "version", nullable = false, length = 40, unique = true)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ModelVersionStatus status = ModelVersionStatus.TRAINING;

    /** Training-service job id, used to poll while the run is in flight. */
    @Column(name = "job_id", length = 64)
    private String jobId;

    /** Version or Hugging Face checkpoint this run started from. */
    @Column(name = "base_checkpoint", length = 200)
    private String baseCheckpoint;

    @Column(name = "training_examples_count", nullable = false)
    @Builder.Default
    private int trainingExamplesCount = 0;

    @Column(name = "validation_count", nullable = false)
    @Builder.Default
    private int validationCount = 0;

    /** Per-label precision/recall/F1 on the held-out split, as returned by the trainer. */
    @Column(name = "eval_metrics", columnDefinition = "text")
    private String evalMetricsJson;

    /** Golden-set regression summary (§17) — pass/fail counts plus failing cases. */
    @Column(name = "golden_report", columnDefinition = "text")
    private String goldenReportJson;

    @Column(name = "macro_f1")
    private Double macroF1;

    /** Result of the §12.4 promotion gate, evaluated against the active version. */
    @Column(name = "gate_passed")
    private Boolean gatePassed;

    @Column(name = "gate_detail", length = 500)
    private String gateDetail;

    @Column(name = "artifact_path", length = 400)
    private String artifactPath;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "trained_at", nullable = false, updatable = false)
    private LocalDateTime trainedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    @Column(name = "promoted_by")
    private UUID promotedBy;

    @PrePersist
    void onCreate() {
        if (trainedAt == null) trainedAt = LocalDateTime.now();
    }
}
