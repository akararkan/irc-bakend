package ak.dev.irc.app.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Privacy-preserving contact matching — the "contact synchronization"
 * signal for friend suggestions.
 *
 * <p>Two kinds of rows share this table:</p>
 * <ul>
 *   <li><b>CONTACT</b> — a SHA-256 hash the owner uploaded from their
 *       address book (client hashes locally; the server never sees a raw
 *       phone number or email address).</li>
 *   <li><b>IDENTITY</b> — the server-computed SHA-256 of the owner's own
 *       registered email (lower-cased, trimmed), written on sync and
 *       backfilled at startup so existing accounts are matchable.</li>
 * </ul>
 *
 * <p>A contact match is then a pure hash join: my CONTACT rows ∩ other
 * users' IDENTITY rows. A <b>bidirectional</b> match (both users have each
 * other saved — the strongest form per the friend-suggestion literature)
 * additionally requires my IDENTITY hash among their CONTACT rows.</p>
 *
 * <p>Phone-number hashes are accepted and stored, but only email identity
 * hashes exist server-side today — phone matching lights up automatically
 * if phone-verified signup ever lands.</p>
 */
@Entity
@Table(name = "user_contact_hashes",
       uniqueConstraints = @UniqueConstraint(name = "uq_contact_hash_owner_hash_kind",
                                             columnNames = {"owner_id", "hash", "kind"}),
       indexes = {
           @Index(name = "idx_contact_hash_lookup", columnList = "hash, kind"),
           @Index(name = "idx_contact_hash_owner",  columnList = "owner_id, kind")
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserContactHash {

    /** Row kinds — plain strings (two fixed values; no enum CHECK-constraint risk). */
    public static final String KIND_CONTACT  = "CONTACT";
    public static final String KIND_IDENTITY = "IDENTITY";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Lower-case hex SHA-256 (64 chars). */
    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
