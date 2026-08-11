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
 *   <li><b>IDENTITY_PHONE</b> — the same construction over the owner's
 *       <em>verified</em> phone number in E.164 <b>without the leading
 *       {@code +}</b>. Written the moment a phone clears OTP verification.</li>
 * </ul>
 *
 * <p>A contact match is then a pure hash join: my CONTACT rows ∩ other
 * users' identity rows. A <b>bidirectional</b> match (both users have each
 * other saved — the strongest form per the friend-suggestion literature)
 * additionally requires one of my identity hashes among their CONTACT rows.</p>
 *
 * <p><b>Why the identity hash is unkeyed.</b> Clients hash their address book
 * locally, so the server can only match on a function the client can also
 * compute — a peppered HMAC is unreproducible client-side and would match
 * nothing. {@code User.phoneHmac} is a keyed hash kept for a different purpose
 * and is deliberately <em>not</em> what matching joins on; the two are not
 * interchangeable.</p>
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

    /** Row kinds — plain strings (fixed values; no enum CHECK-constraint risk). */
    public static final String KIND_CONTACT = "CONTACT";
    /** The owner's registered email identity. Name kept for row compatibility. */
    public static final String KIND_IDENTITY = "IDENTITY";
    /** The owner's verified phone identity (E.164, no leading {@code +}). */
    public static final String KIND_IDENTITY_PHONE = "IDENTITY_PHONE";

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
