package ak.dev.irc.app.settings.data.enums;

/** Lifecycle of a personal-data export job (spec §16). */
public enum ExportStatus {
    PENDING,
    RUNNING,
    READY,
    FAILED,
    EXPIRED
}
