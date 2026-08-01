package ak.dev.irc.app.settings.data.enums;

/**
 * Account-deletion state machine (spec §16):
 * {@code ACTIVE → PENDING_DELETION → ANONYMIZED → PURGED}, with CANCELLED as the
 * recovery exit during the 30-day grace.
 */
public enum DeletionStatus {
    PENDING_DELETION,
    CANCELLED,
    ANONYMIZED,
    PURGED
}
