package ak.dev.irc.app.chat.enums;

/**
 * Who may perform a settings-gated group action (add members, edit info, pin).
 * Stored inside the conversation's JSONB settings so new knobs need no migration.
 */
public enum MemberScope {
    ALL_MEMBERS,
    ADMINS_ONLY
}
