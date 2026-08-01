package ak.dev.irc.app.settings.discovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.util.UUID;

/**
 * Search & discovery flags (spec §6). One row per user controlling how
 * discoverable they are. The reference design denormalises these into the
 * Elasticsearch document so an undiscoverable user costs zero extra queries;
 * this row is the source of truth and the {@code // SEAM} to that ES projection
 * (publish {@code profile.updated} → indexer patches {@code discover.*}).
 */
@Entity
@Table(name = "user_discoverability")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDiscoverability {

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @Column(name = "by_username", nullable = false,
            columnDefinition = "boolean not null default true")
    @Builder.Default
    private boolean byUsername = true;

    @Column(name = "by_phone", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean byPhone = false;

    @Column(name = "by_email", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean byEmail = false;

    @Column(name = "by_qr", nullable = false,
            columnDefinition = "boolean not null default true")
    @Builder.Default
    private boolean byQr = true;

    /** When false, the public profile emits {@code X-Robots-Tag: noindex} and is
     *  omitted from {@code sitemap.xml} (enforced in the Next.js route). */
    @Column(name = "indexable", nullable = false,
            columnDefinition = "boolean not null default true")
    @Builder.Default
    private boolean indexable = true;

    public static UserDiscoverability defaultsFor(UUID userId) {
        return UserDiscoverability.builder().userId(userId).build();
    }
}
