package ak.dev.irc.app.user.enums;

/**
 * The complete authorisation surface of the platform.
 *
 * <ul>
 *   <li>{@link #USER}       — default for every signed-up account.</li>
 *   <li>{@link #RESEARCHER} — can publish research papers; carries the
 *       "Researcher" badge automatically.</li>
 *   <li>{@link #SCHOLAR}    — full scholar privileges (also implies
 *       RESEARCHER via {@code User.getAuthorities()}); carries the
 *       "Scholar" badge automatically.</li>
 *   <li>{@link #ADMIN}      — platform operator. Gates {@code /api/v1/admin/*}.</li>
 * </ul>
 *
 * The older MODERATOR / EDITOR / SUPER_ADMIN tiers were removed when the
 * AccountType / VerificationTier complexity was retired — every call site
 * that referenced them now falls back to ADMIN.
 */
public enum Role {
    USER,
    RESEARCHER,
    SCHOLAR,
    ADMIN
}
