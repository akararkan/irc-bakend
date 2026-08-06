package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the media module ({@code app/media}).
 * Catalog: docs/errors/user-facing-messages.md §1 `media`. Exemplar module
 * for the registry conventions — see {@link ak.dev.irc.app.common.messages}
 * package javadoc.
 */
public final class MediaMessages {

    private MediaMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String MEDIA_TOO_LARGE       = "MEDIA_TOO_LARGE";
    public static final String STORAGE_UNAVAILABLE   = "STORAGE_UNAVAILABLE";
    public static final String MEDIA_RAW_MISSING     = "MEDIA_RAW_MISSING";
    public static final String NOT_MEDIA_OWNER       = "NOT_MEDIA_OWNER";
    public static final String MEDIA_QUOTA_EXCEEDED  = "MEDIA_QUOTA_EXCEEDED";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String MEDIA_TOO_LARGE_MSG =
            "File exceeds the maximum size for %s (%s bytes).";
    public static final String STORAGE_UNAVAILABLE_MSG =
            "Media storage is not configured on this server.";
    public static final String MEDIA_RAW_MISSING_MSG =
            "Uploaded file was not found. Re-upload and retry.";
    public static final String NOT_MEDIA_OWNER_MSG =
            "You do not own this media.";
    public static final String MEDIA_QUOTA_EXCEEDED_MSG =
            "Daily upload quota reached (%s per day for your account tier). "
                    + "The quota resets at midnight UTC.";
}
