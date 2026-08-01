package ak.dev.irc.app.settings.privacy.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A per-user hidden keyword (spec §13). Applied at feed assembly (server-side,
 * so the item never reaches the device) and at notification fan-out. The stored
 * value is the <em>normalized</em> form (case-folded, diacritic-stripped,
 * Arabic/Kurdish letter variants unified) so orthography can't trivially bypass
 * the filter — see
 * {@link ak.dev.irc.app.settings.privacy.service.KeywordNormalizer}.
 */
@Entity
@Table(name = "hidden_keywords",
       uniqueConstraints = @UniqueConstraint(name = "uq_hidden_keyword_user",
               columnNames = {"user_id", "keyword_normalized"}),
       indexes = @Index(name = "idx_hidden_keyword_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HiddenKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Original text the user typed, for display in the settings UI. */
    @Column(name = "keyword_display", nullable = false, length = 100)
    private String keywordDisplay;

    /** Normalized match form. */
    @Column(name = "keyword_normalized", nullable = false, length = 100)
    private String keywordNormalized;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
