package ak.dev.irc.app.user.enums;

/**
 * The complete badge set. Each badge is automatically derived from the
 * user's {@link Role} — there's no separate verification workflow:
 * <ul>
 *   <li>{@link #VERIFIED_SCHOLAR}     — every user with {@code role = SCHOLAR}.</li>
 *   <li>{@link #VERIFIED_RESEARCHER}  — every user with {@code role = RESEARCHER}.</li>
 * </ul>
 * (A SCHOLAR receives only the Scholar badge — the role already implies
 * researcher privileges, so showing both would be noisy.)
 */
public enum BadgeType {
    VERIFIED_SCHOLAR,
    VERIFIED_RESEARCHER
}
