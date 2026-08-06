package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the common, audit and config modules
 * ({@code app/common}, {@code app/audit}, {@code app/config}).
 * Catalog: docs/errors/user-facing-messages.md §1 (`common/*`, `audit`,
 * ops NOTE rows). Conventions: see {@link ak.dev.irc.app.common.messages}
 * package javadoc.
 */
public final class CommonMessages {

    private CommonMessages() {}

    // ── message text ─────────────────────────────────────────────────────
    /**
     * SocialGuard block-policy rejection — deliberately vague so it never
     * reveals who blocked whom. The error CODE is supplied by each caller
     * (e.g. {@code BLOCKED}, {@code *_BLOCKED_RELATIONSHIP}) and lives in
     * that module's registry.
     */
    public static final String INTERACTION_NOT_ALLOWED_MSG =
            "This interaction is not allowed.";

    /**
     * Audit SSE stream subscribe without an authenticated principal
     * (code {@code AUTH_UNAUTHORIZED} via the 1-arg UnauthorizedException).
     */
    public static final String ADMIN_AUTH_REQUIRED_MSG =
            "Admin authentication required";

    // ── notification copy ────────────────────────────────────────────────
    /**
     * Daily trending-digest notification title. The body is loop-assembled
     * from the day's tags at the call site (TrendingNotificationJob).
     */
    public static final String NOTIF_TRENDING_DIGEST_TITLE =
            "Today's trending in scholarship";

    // ── response notes (200 bodies) ──────────────────────────────────────
    /** Ops-report note on the enum-CHECK reconciler startup outcome. */
    public static final String NOTE_ENUM_CHECK_RECONCILER =
            "Runs once per boot; DROP CONSTRAINT IF EXISTS is idempotent. "
                    + "When widening an @Enumerated(STRING) enum on an existing table, add its "
                    + "(table, <table>_<column>_check) pair to STALE_ENUM_CHECKS.";
}
