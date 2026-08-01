package ak.dev.irc.app.settings.notification.service;

import ak.dev.irc.app.settings.notification.enums.NotificationChannel;

import java.util.Set;

/**
 * Platform default for a notification (eventType, channel) pair (spec §8). An
 * absent preference row resolves here, so a new event type is never silently
 * "off" — and a user with no saved preferences still gets sensible delivery.
 */
public final class NotificationDefaults {

    private NotificationDefaults() {}

    /**
     * Event keys that ALWAYS deliver on every channel and ignore DND
     * (spec §8, §12): a user must always learn that a new device signed in or
     * that their account was actioned. Stated as a hard rule, not a default.
     */
    public static final Set<String> BYPASS_ALL = Set.of(
            "SECURITY_ALERT", "LOGIN_ALERT", "ACCOUNT_WARNING");

    public static boolean bypasses(String eventType) {
        return eventType != null && BYPASS_ALL.contains(eventType.toUpperCase());
    }

    /** Default enabled state for a normal (non-bypass) event on a channel. */
    public static boolean defaultEnabled(String eventType, NotificationChannel channel) {
        if (bypasses(eventType)) return true;
        return switch (channel) {
            case IN_APP, DESKTOP, PUSH -> true;   // free, expected
            case EMAIL -> true;                    // on by default, user can mute
            case SMS   -> false;                   // costs money — opt-in only
        };
    }
}
