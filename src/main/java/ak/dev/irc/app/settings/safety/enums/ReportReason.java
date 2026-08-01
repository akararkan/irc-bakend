package ak.dev.irc.app.settings.safety.enums;

/**
 * Why a user is reporting a target (spec §18). Duplicate reports against the
 * same target are grouped by {@code (targetId, reason)} so moderators see one
 * queue item with a count rather than hundreds of identical rows.
 */
public enum ReportReason {
    SPAM,
    HARASSMENT,
    HATE_SPEECH,
    MISINFORMATION,
    NUDITY_SEXUAL,
    VIOLENCE,
    IMPERSONATION,
    SELF_HARM,
    COPYRIGHT,
    OTHER
}
