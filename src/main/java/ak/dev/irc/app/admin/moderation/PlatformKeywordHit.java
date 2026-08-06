package ak.dev.irc.app.admin.moderation;

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

/** A FLAG-severity keyword match — feeds the unified moderation queue. */
@Entity
@Table(name = "platform_keyword_hits",
        indexes = {
                @Index(name = "idx_kw_hit_open", columnList = "resolved, created_at"),
                @Index(name = "idx_kw_hit_keyword", columnList = "keyword_normalized, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformKeywordHit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_ref", nullable = false, length = 64)
    private String targetRef;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "keyword_normalized", nullable = false, length = 100)
    private String keywordNormalized;

    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private boolean resolved = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
