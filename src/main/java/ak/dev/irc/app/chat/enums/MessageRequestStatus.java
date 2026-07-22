package ak.dev.irc.app.chat.enums;

/**
 * State of a Messenger-style message request — the inbox for first contact from
 * someone you are not connected to.
 */
public enum MessageRequestStatus {
    /** Awaiting the recipient's decision. */
    PENDING,
    /** Recipient accepted — the thread graduates to a normal chat. */
    ACCEPTED,
    /** Recipient declined — the thread is hidden. */
    DECLINED,
    /** Recipient declined and blocked the requester. */
    BLOCKED
}
