package ak.dev.irc.app.chat.enums;

/**
 * A member's authority within a GROUP conversation. Orthogonal to
 * {@link MemberStatus}. DIRECT conversations always store both participants as
 * {@link #MEMBER}.
 */
public enum MemberRole {
    /** The creator (exactly one). Ultimate authority; only role that can transfer
     *  ownership or delete the group. */
    OWNER,
    /** Promoted by owner/admin. Manages members and content per the permission
     *  matrix; cannot act on the owner or on another admin. */
    ADMIN,
    /** Everyone else. Sends and reads unless the group is in admins-only mode. */
    MEMBER
}
