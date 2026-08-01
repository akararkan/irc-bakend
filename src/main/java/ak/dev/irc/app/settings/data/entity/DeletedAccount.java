package ak.dev.irc.app.settings.data.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tombstone retained after a purge (spec §16, "What is intentionally retained").
 * The id equals the old user id and prevents id reuse; nothing else is kept.
 */
@Entity
@Table(name = "deleted_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeletedAccount {

    /** The old user id — NOT generated. */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;
}
