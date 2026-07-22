package ak.dev.irc.app.chat.enums;

/**
 * A member's lifecycle state within a conversation. Orthogonal to
 * {@link MemberRole}.
 */
public enum MemberStatus {
    /** Full read + write access. */
    ACTIVE,
    /** Read-only — muted by an admin. Can read the timeline; the send-permission
     *  engine denies posting. */
    RESTRICTED,
    /** Left voluntarily. Read access stops; may be re-added. */
    LEFT,
    /** Kicked by an admin/owner. Read access stops immediately. */
    REMOVED
}
