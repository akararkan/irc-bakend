package ak.dev.irc.app.settings.notification.enums;

/**
 * Delivery channels a notification event can fan out to (spec §8). Each
 * (eventType, channel) pair is independently toggleable in the preference
 * matrix.
 */
public enum NotificationChannel {
    PUSH,
    IN_APP,
    EMAIL,
    SMS,
    DESKTOP;

    public static NotificationChannel parse(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
