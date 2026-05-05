package ak.dev.irc.app.user.realtime;

import java.util.List;
import java.util.UUID;

/**
 * Fired when notifications are marked read or removed. Used to keep multiple
 * open tabs in sync — when one tab clears a notification, the others should
 * remove it from view too.
 *
 * <p>{@code ids} can be empty when {@code allRead} is {@code true} — the
 * client should mark its entire local cache as read in that case.</p>
 */
public record NotificationReadEvent(
        UUID recipientId,
        List<UUID> ids,
        boolean allRead,
        boolean deleted
) {}
