package ak.dev.irc.app.chat.enums;

/** Lifecycle of a channel/group join request (invite links or public
 *  join-by-request channels). */
public enum JoinRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
