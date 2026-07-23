package ak.dev.irc.app.chat.enums;

/** Lifecycle of a scheduled (send-later) message. */
public enum ScheduledMessageStatus {
    PENDING,   // waiting for its scheduled time
    SENT,      // delivered by the scheduler
    CANCELLED, // cancelled by the author before it fired
    FAILED     // permission/validation failed at fire time
}
