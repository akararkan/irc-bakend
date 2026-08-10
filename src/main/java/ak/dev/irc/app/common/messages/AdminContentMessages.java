package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the admin content-plane modules
 * ({@code app/admin/content}, {@code moderation}, {@code sound},
 * {@code research}, {@code qna}, {@code trending}, {@code knowledge},
 * {@code activity}, {@code discovery}).
 * Catalog: docs/errors/user-facing-messages.md §1 {@code admin/*} tables,
 * §2 notes/warnings, §3 notifications. Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class AdminContentMessages {

    private AdminContentMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String AUTHOR_SCOPE_REQUIRED      = "AUTHOR_SCOPE_REQUIRED";
    public static final String POST_NOT_REMOVED           = "POST_NOT_REMOVED";
    public static final String INVALID_INPUT              = "INVALID_INPUT";
    public static final String INVALID_KEYWORD            = "INVALID_KEYWORD";
    public static final String CONTENT_BLOCKED_BY_POLICY  = "CONTENT_BLOCKED_BY_POLICY";
    public static final String INVALID_BULK_ACTION        = "INVALID_BULK_ACTION";
    public static final String INVALID_BULK_TARGET        = "INVALID_BULK_TARGET";
    public static final String INVALID_IMPORT_BATCH       = "INVALID_IMPORT_BATCH";
    public static final String INVALID_IMPORT_ITEM        = "INVALID_IMPORT_ITEM";
    public static final String SOUND_FILE_INVALID         = "SOUND_FILE_INVALID";
    public static final String INVALID_STATUS             = "INVALID_STATUS";
    public static final String INVALID_CATEGORY           = "INVALID_CATEGORY";
    public static final String INVALID_SORT               = "INVALID_SORT";
    public static final String INVALID_SCOPE              = "INVALID_SCOPE";
    public static final String INVALID_TYPE               = "INVALID_TYPE";
    public static final String INVALID_TAG                = "INVALID_TAG";
    public static final String DUPLICATE_VOCAB_NAME       = "DUPLICATE_VOCAB_NAME";
    public static final String INVALID_KIND               = "INVALID_KIND";
    public static final String CASE_NOT_PENDING           = "CASE_NOT_PENDING";
    public static final String DUAL_CONTROL_REQUIRED      = "DUAL_CONTROL_REQUIRED";
    public static final String BREAKGLASS_CASE_REQUIRED   = "BREAKGLASS_CASE_REQUIRED";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String AUTHOR_SCOPE_REQUIRED_MSG =
            "authorId is required — posts are partitioned by author. "
                    + "Use search or reports to locate a post id directly.";
    public static final String POST_NOT_REMOVED_MSG =
            "Only REMOVED posts can be restored.";
    /** {@code INVALID_INPUT} on blocklist add with a blank keyword. */
    public static final String INVALID_INPUT_KEYWORD_MSG =
            "keyword is required";
    /** {@code INVALID_INPUT} on research flag with no type. */
    public static final String INVALID_INPUT_FLAG_TYPE_MSG =
            "type is required (PLAGIARISM or QUALITY).";
    public static final String INVALID_KEYWORD_MSG =
            "Keyword normalizes to nothing.";
    public static final String CONTENT_BLOCKED_BY_POLICY_MSG =
            "This content violates platform policy and cannot be published.";
    public static final String INVALID_BULK_ACTION_MSG =
            "Unknown action. Allowed: TAKEDOWN, RESTORE, DELETE, SOUND_APPROVE, SOUND_REJECT.";
    /** {@code INVALID_BULK_TARGET} when a bulk DELETE targets a non-COMMENT/STORY type. */
    public static final String INVALID_BULK_TARGET_DELETE_MSG =
            "DELETE supports COMMENT and STORY targets.";
    /** {@code INVALID_BULK_TARGET} when a bulk action needs a specific target type. */
    public static final String INVALID_BULK_TARGET_NEEDS_MSG =
            "This action needs a %s target.";
    public static final String INVALID_IMPORT_BATCH_MSG =
            "Provide 1–100 items.";
    public static final String INVALID_IMPORT_ITEM_MSG =
            "Each item needs title and audioUrl.";
    public static final String SOUND_AUDIO_FILE_REQUIRED_MSG =
            "Provide an audio file.";
    public static final String SOUND_AUDIO_TYPE_INVALID_MSG =
            "The sound file must be audio (mp3, m4a, aac, wav, ogg, opus or mp4).";
    public static final String SOUND_COVER_TYPE_INVALID_MSG =
            "The cover art must be an image.";
    public static final String INVALID_STATUS_MSG =
            "Unknown status. Allowed: %s";
    public static final String INVALID_CATEGORY_MSG =
            "Unknown category. Allowed: %s";
    public static final String INVALID_SORT_MSG =
            "Unknown 'by'. Allowed: downloads, citations.";
    public static final String INVALID_SCOPE_MSG =
            "Unknown scope. Allowed: %s";
    /** {@code INVALID_TYPE} on a trending override. */
    public static final String INVALID_TYPE_PIN_BAN_MSG =
            "Unknown type. Allowed: PIN, BAN.";
    /** {@code INVALID_TYPE} on an activity-erase body. */
    public static final String INVALID_TYPE_ACTIVITY_MSG =
            "Unknown activity type.";
    public static final String INVALID_TAG_MSG =
            "Invalid tag.";
    /** {@code DUPLICATE_VOCAB_NAME} for topics. */
    public static final String DUPLICATE_VOCAB_NAME_TOPIC_MSG =
            "A topic named '%s' already exists.";
    /** {@code DUPLICATE_VOCAB_NAME} for madhhabs. */
    public static final String DUPLICATE_VOCAB_NAME_MADHHAB_MSG =
            "A madhhab named '%s' already exists.";
    public static final String INVALID_KIND_MSG =
            "Unknown kind. Allowed: %s";
    public static final String CASE_NOT_PENDING_MSG =
            "Case is not pending approval.";
    public static final String DUAL_CONTROL_REQUIRED_MSG =
            "Dual control: a break-glass case cannot be approved by the admin who opened it.";
    public static final String BREAKGLASS_CASE_REQUIRED_MSG =
            "Break-glass access requires an OPEN dual-control case for this user "
                    + "(POST /api/v1/admin/breakglass/{userId}, approved by a second admin). "
                    + "The default is no access.";

    // ── notes / warnings inside 200 bodies ──────────────────────────────
    public static final String NOTE_ACTIVITY_ERASE_CAP =
            "hard cap 10k rows per call — repeat for heavier histories";
    public static final String NOTE_CONTACT_HASHES_SHA256 =
            "Hashes are SHA-256 of phone/email — the server never sees raw contacts.";
    public static final String NOTE_IDENTITY_HASH_BACKFILL =
            "Identity-hash backfill makes every active account matchable-by-default; "
                    + "discovery flags gate whether a match is surfaced.";
    public static final String NOTE_CONTACT_SYNC_RATE_LIMIT =
            "The contact:sync rate limit (3/24h) FAILS OPEN if Redis is down.";
    public static final String WARN_QR_RESOLVE_SEAM =
            "QR-resolve does not yet consult discover.byQr — "
                    + "a rotated flag does not invalidate resolves; rotation (below) does.";

    // ── notification copy (sendSystemNotification) ──────────────────────
    public static final String NOTIF_POST_REMOVED_TITLE      = "Your post was removed";
    public static final String NOTIF_POST_RESTORED_TITLE     = "Your post was restored";
    public static final String NOTIF_POST_RESTORED_BODY =
            "A previously removed post of yours has been restored.";
    public static final String NOTIF_COMMENT_REMOVED_TITLE   = "Your comment was removed";
    public static final String NOTIF_STORY_REMOVED_TITLE     = "Your story was removed";
    public static final String NOTIF_RESEARCH_UNPUBLISHED_TITLE = "Your research was unpublished";
    public static final String NOTIF_RESEARCH_UNPUBLISHED_BODY =
            "Your research \"%s\" was unpublished by moderation%s.";
    public static final String NOTIF_RESEARCH_RETRACTED_TITLE   = "Your research was retracted";
    public static final String NOTIF_RESEARCH_RETRACTED_BODY =
            "Your research \"%s\" was retracted by moderation%s.";
    public static final String NOTIF_RESEARCH_REMOVED_TITLE     = "Your research was removed";
    public static final String NOTIF_RESEARCH_REMOVED_BODY =
            "Your research \"%s\" was removed by moderation%s.";
}
