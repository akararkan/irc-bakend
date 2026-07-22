package ak.dev.irc.app.chat.enums;

/**
 * Outcome of the send-permission engine for a DIRECT message, evaluated
 * <b>before</b> the message is persisted.
 */
public enum SendDecision {
    /** Normal write + fan-out + push (subject to mute). */
    ALLOW,
    /** Stranger's first contact — create the thread but quarantine it as a
     *  pending message request; no push, no receipts/typing/presence leaked. */
    ROUTE_TO_REQUEST,
    /** Recipient has restricted the sender — write normally but the recipient's
     *  client files it in a muted tray; the sender gets no delivery/read signal. */
    DELIVER_RESTRICTED,
    /** A block relationship exists — the message is not written. Never reveals
     *  who blocked whom. */
    DENY
}
