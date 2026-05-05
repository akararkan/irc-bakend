package ak.dev.irc.app.user.realtime;

import java.util.UUID;

/**
 * Fired (within a transaction) whenever the unread count for a user changes —
 * after a new notification is saved, after a mark-read, after a delete. An
 * AFTER_COMMIT listener forwards the value to the user's SSE channel so every
 * open tab refreshes its badge in real time.
 */
public record NotificationUnreadCountEvent(UUID recipientId, long unreadCount) {}
