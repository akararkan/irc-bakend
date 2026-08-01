package ak.dev.irc.app.settings.safety.enums;

/**
 * Lifecycle of a user report (spec §18):
 * <pre>
 * SUBMITTED → TRIAGED → ACTIONED | DISMISSED → (APPEALED → UPHELD | REVERSED)
 * </pre>
 */
public enum ReportState {
    SUBMITTED,
    TRIAGED,
    ACTIONED,
    DISMISSED,
    APPEALED,
    UPHELD,
    REVERSED
}
