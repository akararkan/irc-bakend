package ak.dev.irc.app.user.enums;

/**
 * The complete authorisation surface of the platform.
 *
 * <ul>
 *   <li>{@link #USER}       — default account tier.</li>
 *   <li>{@link #RESEARCHER} — can publish research papers; carries the
 *       "Researcher" badge automatically.</li>
 *   <li>{@link #SCHOLAR}    — full scholar privileges (also implies
 *       RESEARCHER via {@code User.getAuthorities()}); carries the
 *       "Scholar" badge automatically.</li>
 *   <li>{@link #MODERATOR}  — staff tier (RBAC widening, architecture.md §6):
 *       RW on content moderation, safety & reports, and the moderation parts
 *       of research/QnA and chat/live; RO on users and logs. Cannot change
 *       roles, touch ops, reindex search, or see analytics exports.</li>
 *   <li>{@link #SUPPORT}    — staff tier: user inspection + report-intake
 *       view; RO on notifications and logs. No takedowns, no role changes,
 *       no ops controls.</li>
 *   <li>{@link #ANALYST}    — staff tier: strictly read-only over analytics,
 *       logs, and search/feed observability. Every mutation is denied.</li>
 *   <li>{@link #ADMIN}      — platform operator. Full access; critical ops
 *       still require step-up. The ONLY role that can grant roles.</li>
 * </ul>
 *
 * <p>The historical SUPER_ADMIN tier was deliberately deleted, not
 * implemented — the phantom {@code hasAnyRole('…','SUPER_ADMIN')} grants that
 * once referenced it were normalized to real roles before this enum widened,
 * so no dormant grant went live with this change. Widening note: the
 * {@code users_role_check} CHECK constraint is dropped by
 * {@code EnumCheckConstraintReconciler} in the same change (the Hibernate
 * enum-CHECK gotcha).</p>
 */
public enum Role {
    USER,
    RESEARCHER,
    SCHOLAR,
    MODERATOR,
    SUPPORT,
    ANALYST,
    ADMIN
}
