package ak.dev.irc.app.settings.presence.enums;

/**
 * Who may see a presence signal (spec §7): online status, last seen, typing.
 *
 * <p>Enforced with a <b>reciprocity</b> rule — a user who hides their last seen
 * must not be able to see other users' last seen, otherwise hiding becomes a
 * one-way spying advantage. The resolver never sends the real value with a
 * "hidden" flag; it sends {@code null}.</p>
 */
public enum PresencePolicy {
    EVERYONE,
    FRIENDS,
    NOBODY;

    public static PresencePolicy parse(String raw, PresencePolicy fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
