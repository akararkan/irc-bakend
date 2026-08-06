package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the settings module ({@code app/settings}).
 * Catalog: docs/errors/user-facing-messages.md §1.32–§1.48 (`settings/core`,
 * `settings/data`, `settings/discovery`, `settings/notification`,
 * `settings/privacy`, `settings/safety`). Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class SettingsMessages {

    private SettingsMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String BAD_TIMEZONE          = "BAD_TIMEZONE";
    public static final String BAD_EVENT_TYPE        = "BAD_EVENT_TYPE";
    public static final String BAD_CHANNEL           = "BAD_CHANNEL";
    public static final String BAD_TIME              = "BAD_TIME";
    public static final String NOT_LIST_OWNER        = "NOT_LIST_OWNER";
    public static final String NOT_EXPORT_OWNER      = "NOT_EXPORT_OWNER";
    public static final String DELETION_PENDING      = "DELETION_PENDING";
    public static final String NOT_REPORTER          = "NOT_REPORTER";
    public static final String REPORT_NOT_APPEALABLE = "REPORT_NOT_APPEALABLE";
    public static final String TARGET_REQUIRED       = "TARGET_REQUIRED";

    // ── message text (templates render with .formatted) ─────────────────
    // settings/core (SettingsService) — default BAD_REQUEST code
    public static final String SETTINGS_BODY_INVALID_MSG =
            "Invalid settings body for %s: %s";
    public static final String SETTINGS_PATCH_INVALID_MSG =
            "Invalid settings patch for %s: %s";
    public static final String SETTINGS_SECTION_UNKNOWN_MSG =
            "Unknown or non-cosmetic settings section: %s";

    // settings/privacy (PrivacyService / HiddenKeywordService / PrivacyListService)
    public static final String PRIVACY_FIELD_UNKNOWN_MSG =
            "Unknown privacy field: %s";
    public static final String VISIBILITY_LEVEL_UNKNOWN_MSG =
            "Unknown visibility level: %s";
    public static final String KEYWORD_BLANK_MSG =
            "Keyword must not be blank.";
    public static final String KEYWORD_NORMALIZES_EMPTY_MSG =
            "Keyword normalizes to empty.";
    public static final String KEYWORD_LIMIT_REACHED_MSG =
            "Hidden-keyword limit reached (%s).";
    public static final String NOT_LIST_OWNER_MSG =
            "You do not own this list.";

    // settings/notification (NotificationSettingsService)
    public static final String BAD_TIMEZONE_MSG =
            "Invalid IANA timezone: %s";
    public static final String BAD_EVENT_TYPE_MSG =
            "Unknown notification event type: %s";
    public static final String BAD_CHANNEL_MSG =
            "Unknown notification channel: %s";
    public static final String BAD_TIME_MSG =
            "Time must be HH:mm — got: %s";

    // settings/discovery (QrTokenService / QrDiscoveryController)
    public static final String QR_CODE_INVALID_MSG =
            "QR code is invalid or has been rotated.";
    public static final String QR_USER_NOT_FOUND_MSG =
            "User not found for this QR code.";

    // settings/data (DataExportService / HistoryService / AccountLifecycleService)
    public static final String NOT_EXPORT_OWNER_MSG =
            "Not your export job.";
    public static final String EXPORT_NOT_READY_MSG =
            "Export not ready or expired.";
    public static final String EXPORT_FILE_UNAVAILABLE_MSG =
            "Export file is no longer available.";
    public static final String HISTORY_TYPE_UNKNOWN_MSG =
            "Unknown history type: %s (expected search|watch)";
    public static final String DELETION_PENDING_MSG =
            "Deletion already requested.";
    public static final String NO_PENDING_DELETION_CANCEL_MSG =
            "No pending deletion to cancel.";
    public static final String NO_PENDING_DELETION_PURGE_MSG =
            "No pending deletion to purge.";
    public static final String NO_PENDING_DELETION_HOLD_MSG =
            "No pending deletion to hold.";

    // settings/safety (ReportService)
    public static final String NOT_REPORTER_MSG =
            "You can only appeal your own reports.";
    public static final String REPORT_NOT_APPEALABLE_MSG =
            "This report cannot be appealed in its current state.";
    public static final String TARGET_REQUIRED_MSG =
            "targetId (or targetRef for MESSAGE targets) is required.";
}
