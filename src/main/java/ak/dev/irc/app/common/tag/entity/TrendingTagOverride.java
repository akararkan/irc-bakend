package ak.dev.irc.app.common.tag.entity;

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
 * Editorial override on the trending-tags surface
 * (docs/admin/search-feed-trending.md §6.2): {@code BAN} drops a tag before
 * the top-K cut, {@code PIN} splices it at a rank after it. Consulted by
 * {@code TrendingTagJob} on every rebuild, so an override takes effect within
 * one refresh interval (≤10 min) with zero read-path changes.
 */
@Entity
@Table(name = "trending_tag_overrides",
        indexes = @Index(name = "idx_trending_override_active", columnList = "revoked_at, scope"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingTagOverride {

    public enum OverrideType {PIN, BAN}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tag_normalized", nullable = false, length = 100)
    private String tagNormalized;

    /** ALL / QUESTION / RESEARCH / POST / REEL — ALL applies to every scope. */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private OverrideType type;

    @Column(name = "pinned_rank")
    private Integer pinnedRank;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }

    public boolean appliesTo(String targetScope) {
        return "ALL".equalsIgnoreCase(scope) || scope.equalsIgnoreCase(targetScope);
    }
}
