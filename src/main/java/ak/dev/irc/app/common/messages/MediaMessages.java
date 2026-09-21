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
    public static final String MEDIA_TYPE_NOT_ALLOWED = "MEDIA_TYPE_NOT_ALLOWED";
    public static final String MEDIA_TOO_MANY        = "MEDIA_TOO_MANY";
    public static final String MEDIA_DURATION_EXCEEDED = "MEDIA_DURATION_EXCEEDED";
    public static final String MEDIA_INVALID         = "MEDIA_INVALID";
    public static final String MEDIA_BUSY            = "MEDIA_BUSY";
    public static final String MEDIA_TYPE_BLOCKED    = "MEDIA_TYPE_BLOCKED";
    public static final String MEDIA_CONTENT_MISMATCH = "MEDIA_CONTENT_MISMATCH";
    public static final String MEDIA_NSFW_BLOCKED    = "MEDIA_NSFW_BLOCKED";
    public static final String MEDIA_MODERATION_UNAVAILABLE = "MEDIA_MODERATION_UNAVAILABLE";
    public static final String UPLOAD_SESSION_NOT_FOUND   = "UPLOAD_SESSION_NOT_FOUND";
    public static final String UPLOAD_SESSION_EXPIRED     = "UPLOAD_SESSION_EXPIRED";
    public static final String UPLOAD_SESSION_INCOMPLETE  = "UPLOAD_SESSION_INCOMPLETE";
    public static final String UPLOAD_CHUNK_INVALID       = "UPLOAD_CHUNK_INVALID";

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
    public static final String MEDIA_TYPE_NOT_ALLOWED_MSG =
            "This file type (%s) is not allowed here.";
    public static final String MEDIA_TOO_MANY_MSG =
            "Too many files — the limit here is %d.";
    public static final String MEDIA_DURATION_EXCEEDED_MSG =
            "Video is too long — the limit here is %d seconds.";
    public static final String MEDIA_INVALID_MSG =
            "The file could not be read as valid media.";
    public static final String MEDIA_BUSY_MSG =
            "Media processing is at capacity — please try again in a moment.";
    public static final String MEDIA_TYPE_BLOCKED_MSG =
            "This file type is not allowed for security reasons (%s).";
    public static final String MEDIA_CONTENT_MISMATCH_MSG =
            "The file's content does not match its declared type.";
    public static final String MEDIA_NSFW_BLOCKED_MSG =
            "This image appears to contain explicit content and can't be uploaded here.";
    public static final String MEDIA_MODERATION_UNAVAILABLE_MSG =
            "Image screening is temporarily unavailable — please try again in a moment.";
    public static final String UPLOAD_SESSION_NOT_FOUND_MSG =
            "Upload session not found.";
    public static final String UPLOAD_SESSION_EXPIRED_MSG =
            "This upload session has expired — start a new upload.";
    public static final String UPLOAD_SESSION_INCOMPLETE_MSG =
            "Upload is incomplete — %d of %d chunks received.";
    public static final String UPLOAD_CHUNK_INVALID_MSG =
            "Invalid upload chunk.";
}
