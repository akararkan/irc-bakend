package ak.dev.irc.app.admin.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Platform-level keyword blocklist (content-moderation.md §2.7) — enforced at
 * content-create time, unlike the per-user {@code hidden_keywords} which is
 * display-side. Normalized with the same Arabic/Kurdish-aware
 * {@code KeywordNormalizer} so variants can't dodge it.
 *
 * <p>Severity: {@code BLOCK} rejects the content at create;
 * {@code FLAG} lets it publish but emits a moderation-queue hit.</p>
 */
@Entity
@Table(name = "platform_keywords",
        uniqueConstraints = @UniqueConstraint(name = "uq_platform_keyword_norm",
                columnNames = "keyword_normalized"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformKeyword {

    public enum Severity {FLAG, BLOCK}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "keyword_display", nullable = false, length = 100)
    private String keywordDisplay;

    @Column(name = "keyword_normalized", nullable = false, length = 100)
    private String keywordNormalized;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private Severity severity;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
