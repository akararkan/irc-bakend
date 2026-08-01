package ak.dev.irc.app.settings.privacy.enums;

/**
 * Who may see a given profile field or content class (spec §5).
 *
 * <p>Ordering is deliberately narrowing (EVERYONE widest → ONLY_ME narrowest);
 * do not rely on {@code ordinal()} for permission maths — the
 * {@link ak.dev.irc.app.settings.privacy.service.VisibilityResolver} evaluates
 * each level explicitly and fail-closed.</p>
 */
public enum VisibilityLevel {

    /** Anyone, including logged-out viewers (subject only to blocks). */
    EVERYONE,

    /** Mutual follow only. */
    FRIENDS,

    /** Anyone who follows the owner. */
    FOLLOWERS,

    /** Members of the owner's Close Friends list. */
    CLOSE_FRIENDS,

    /** Members of one of the owner's custom privacy lists. */
    CUSTOM,

    /** Owner only — nobody else. */
    ONLY_ME;

    public static VisibilityLevel parse(String raw, VisibilityLevel fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
