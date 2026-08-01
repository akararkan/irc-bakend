package ak.dev.irc.app.settings.safety.enums;

/**
 * The action a moderator took against a target (spec §18).
 *
 * <p><b>Privacy note:</b> this is the target's private moderation record. The
 * reporter is NEVER told which specific action was taken — disclosing it enables
 * harassment campaigns. Reporters only ever see a coarse outcome bucket.</p>
 */
public enum Resolution {
    NONE,
    WARNING_ISSUED,
    CONTENT_REMOVED,
    ACCOUNT_SUSPENDED,
    NO_ACTION
}
