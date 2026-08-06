package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the research module ({@code app/research}).
 * Catalog: docs/errors/user-facing-messages.md §1.57 `research` (plus §1.58
 * cross-cutting and §1.59 storage backend). Follows the registry conventions —
 * see {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class ResearchMessages {

    private ResearchMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String ALREADY_ARCHIVED               = "ALREADY_ARCHIVED";
    public static final String ALREADY_DELETED                = "ALREADY_DELETED";
    public static final String ALREADY_PUBLISHED              = "ALREADY_PUBLISHED";
    public static final String COMMENTS_DISABLED              = "COMMENTS_DISABLED";
    public static final String COMMENT_DATA_ERROR             = "COMMENT_DATA_ERROR";
    public static final String COMMENT_DELETED                = "COMMENT_DELETED";
    public static final String COMMENT_TOO_LONG               = "COMMENT_TOO_LONG";
    public static final String CONSTRAINT_VIOLATION           = "CONSTRAINT_VIOLATION";
    public static final String CONTRIBUTOR_DELETED            = "CONTRIBUTOR_DELETED";
    public static final String CONTRIBUTOR_IS_OWNER           = "CONTRIBUTOR_IS_OWNER";
    public static final String CONTRIBUTOR_NOT_ELIGIBLE       = "CONTRIBUTOR_NOT_ELIGIBLE";
    public static final String CONTRIBUTOR_RESEARCH_MISMATCH  = "CONTRIBUTOR_RESEARCH_MISMATCH";
    public static final String COVER_UPLOAD_ERROR             = "COVER_UPLOAD_ERROR";
    public static final String COVER_UPLOAD_FAILED            = "COVER_UPLOAD_FAILED";
    public static final String DB_ERROR                       = "DB_ERROR";
    public static final String DOWNLOADS_DISABLED             = "DOWNLOADS_DISABLED";
    public static final String DUPLICATE_CONTRIBUTOR          = "DUPLICATE_CONTRIBUTOR";
    public static final String EMPTY_COMMENT                  = "EMPTY_COMMENT";
    public static final String EMPTY_FILE                     = "EMPTY_FILE";
    public static final String FILE_NOT_AVAILABLE             = "FILE_NOT_AVAILABLE";
    public static final String FILE_TOO_LARGE                 = "FILE_TOO_LARGE";
    public static final String FILE_UPLOAD_ERROR              = "FILE_UPLOAD_ERROR";
    public static final String INVALID_FILENAME               = "INVALID_FILENAME";
    public static final String INVALID_FILE_TYPE              = "INVALID_FILE_TYPE";
    public static final String INVALID_INPUT                  = "INVALID_INPUT";
    public static final String INVALID_PARENT                 = "INVALID_PARENT";
    public static final String INVALID_QUERY                  = "INVALID_QUERY";
    public static final String INVALID_REACTION               = "INVALID_REACTION";
    public static final String INVALID_SCHEDULE               = "INVALID_SCHEDULE";
    public static final String INVALID_TAGS                   = "INVALID_TAGS";
    public static final String MEDIA_ADD_ERROR                = "MEDIA_ADD_ERROR";
    public static final String MEDIA_DELETE_ERROR             = "MEDIA_DELETE_ERROR";
    public static final String MEDIA_METADATA_ERROR           = "MEDIA_METADATA_ERROR";
    public static final String MEDIA_NOT_FOUND                = "MEDIA_NOT_FOUND";
    public static final String MEDIA_UPLOAD_FAILED            = "MEDIA_UPLOAD_FAILED";
    public static final String MISSING_ABSTRACT               = "MISSING_ABSTRACT";
    public static final String MISSING_COLLECTION_NAME        = "MISSING_COLLECTION_NAME";
    public static final String MISSING_FILENAME               = "MISSING_FILENAME";
    public static final String MISSING_MEDIA_ID               = "MISSING_MEDIA_ID";
    public static final String MISSING_NEW_NAME               = "MISSING_NEW_NAME";
    public static final String MISSING_OLD_NAME               = "MISSING_OLD_NAME";
    public static final String MISSING_RESEARCHER_ID          = "MISSING_RESEARCHER_ID";
    public static final String MISSING_RESEARCH_ID            = "MISSING_RESEARCH_ID";
    public static final String MISSING_SLUG                   = "MISSING_SLUG";
    public static final String MISSING_SOURCE_ID              = "MISSING_SOURCE_ID";
    public static final String MISSING_TAGS                   = "MISSING_TAGS";
    public static final String MISSING_TITLE                  = "MISSING_TITLE";
    public static final String MISSING_TOKEN                  = "MISSING_TOKEN";
    public static final String MISSING_USER_ID                = "MISSING_USER_ID";
    public static final String NOT_HIDDEN                     = "NOT_HIDDEN";
    public static final String NOT_PUBLISHED                  = "NOT_PUBLISHED";
    public static final String NULL_REQUEST_BODY              = "NULL_REQUEST_BODY";
    public static final String PARENT_DELETED                 = "PARENT_DELETED";
    public static final String SOURCE_MISMATCH                = "SOURCE_MISMATCH";
    public static final String SOURCE_UPLOAD_ERROR            = "SOURCE_UPLOAD_ERROR";
    public static final String SOURCE_UPLOAD_FAILED           = "SOURCE_UPLOAD_FAILED";
    public static final String STORAGE_UNAVAILABLE            = "STORAGE_UNAVAILABLE";
    public static final String TAG_FETCH_ERROR                = "TAG_FETCH_ERROR";
    public static final String THUMBNAIL_UPLOAD_FAILED        = "THUMBNAIL_UPLOAD_FAILED";
    public static final String UNEXPECTED_ERROR               = "UNEXPECTED_ERROR";
    public static final String URL_GENERATION_ERROR           = "URL_GENERATION_ERROR";
    public static final String VIDEO_UPLOAD_ERROR             = "VIDEO_UPLOAD_ERROR";
    public static final String VIDEO_UPLOAD_FAILED            = "VIDEO_UPLOAD_FAILED";

    // block-relationship guard codes (message lives in SocialGuard)
    public static final String RESEARCH_REACTION_BLOCKED_RELATIONSHIP =
            "RESEARCH_REACTION_BLOCKED_RELATIONSHIP";
    public static final String RESEARCH_COMMENT_BLOCKED_RELATIONSHIP =
            "RESEARCH_COMMENT_BLOCKED_RELATIONSHIP";
    public static final String RESEARCH_COMMENT_REACTION_BLOCKED_RELATIONSHIP =
            "RESEARCH_COMMENT_REACTION_BLOCKED_RELATIONSHIP";
    public static final String RESEARCH_SAVE_BLOCKED_RELATIONSHIP =
            "RESEARCH_SAVE_BLOCKED_RELATIONSHIP";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String ALREADY_ARCHIVED_MSG =
            "Research is already archived";
    public static final String ALREADY_DELETED_MSG =
            "Comment is already deleted";
    public static final String ALREADY_PUBLISHED_MSG =
            "Research is already published";
    public static final String COMMENTS_DISABLED_MSG =
            "Comments are disabled for this research";
    public static final String COMMENT_DATA_ERROR_MSG =
            "Invalid comment data";
    public static final String COMMENT_DELETED_EDIT_MSG =
            "Cannot edit a deleted comment";
    public static final String COMMENT_DELETED_HIDE_MSG =
            "Cannot hide a deleted comment";
    public static final String COMMENT_DELETED_REACT_MSG =
            "Cannot react to a deleted comment";
    public static final String COMMENT_TOO_LONG_MSG =
            "Comment exceeds maximum length of 5000 characters";
    public static final String CONSTRAINT_VIOLATION_MSG =
            "Update violates data constraints";
    public static final String CONTRIBUTOR_DELETED_MSG =
            "Contributor account is deactivated";
    public static final String CONTRIBUTOR_IS_OWNER_LISTED_MSG =
            "The corresponding researcher cannot also be listed as a contributor";
    public static final String CONTRIBUTOR_IS_OWNER_ADDED_MSG =
            "The corresponding researcher cannot be added as a contributor";
    public static final String CONTRIBUTOR_NOT_ELIGIBLE_MSG =
            "Contributors must be a researcher or scholar (user %s)";
    public static final String CONTRIBUTOR_RESEARCH_MISMATCH_MSG =
            "Contributor does not belong to this research";
    public static final String COVER_UPLOAD_ERROR_MSG =
            "Failed to upload cover image";
    public static final String DB_ERROR_MSG =
            "Failed to create research due to a database error";
    public static final String DOWNLOADS_DISABLED_MSG =
            "Downloads are disabled for this research";
    public static final String DUPLICATE_CONTRIBUTOR_MSG =
            "Duplicate contributor in request: %s";
    public static final String EMPTY_COMMENT_NO_CONTENT_MSG =
            "Comment must have text, media, or voice content";
    public static final String EMPTY_COMMENT_BLANK_MSG =
            "Comment content cannot be empty";
    public static final String EMPTY_FILE_REQUIRED_MSG =
            "%s file is required and cannot be empty";
    public static final String EMPTY_FILE_ZERO_BYTES_MSG =
            "File cannot be empty (0 bytes)";
    public static final String FILE_NOT_AVAILABLE_MSG =
            "Media file not available for download";
    public static final String FILE_TOO_LARGE_MSG =
            "File size exceeds maximum allowed limit";
    public static final String FILE_UPLOAD_ERROR_MSG =
            "File upload failed";
    public static final String INVALID_FILENAME_MSG =
            "Invalid file name";
    public static final String INVALID_FILE_TYPE_MSG =
            "Invalid file type. Allowed: %s";
    public static final String INVALID_INPUT_RESEARCH_ID_AND_BODY_MSG =
            "Research ID and request body are required";
    public static final String INVALID_INPUT_MEDIA_ID_AND_BODY_MSG =
            "Media ID and request body are required";
    public static final String INVALID_INPUT_COMMENT_ID_AND_BODY_MSG =
            "Comment ID and request body are required";
    public static final String INVALID_INPUT_COMMENT_AND_USER_MSG =
            "Comment ID and User ID are required";
    public static final String INVALID_INPUT_RESEARCH_AND_USER_MSG =
            "Research ID and User ID are required";
    public static final String INVALID_INPUT_RESEARCH_AND_COMMENT_MSG =
            "Research ID and comment data are required";
    public static final String INVALID_INPUT_CONTRIBUTOR_USER_ID_MSG =
            "Contributor userId is required";
    public static final String INVALID_INPUT_BODY_REQUIRED_MSG =
            "Request body is required";
    public static final String INVALID_PARENT_MSG =
            "Parent comment does not belong to this research";
    public static final String INVALID_QUERY_MSG =
            "Search query must be at least 2 characters";
    public static final String INVALID_REACTION_MSG =
            "Research ID is required";
    public static final String INVALID_SCHEDULE_MSG =
            "Scheduled publish time must be in the future";
    public static final String INVALID_TAGS_MSG =
            "Tags cannot be empty";
    public static final String MEDIA_ADD_ERROR_MSG =
            "Failed to add media file";
    public static final String MEDIA_DELETE_ERROR_MSG =
            "Failed to remove media file";
    public static final String MEDIA_METADATA_ERROR_MSG =
            "Invalid media metadata";
    public static final String MEDIA_NOT_FOUND_MSG =
            "Failed to retrieve media file";
    public static final String MISSING_ABSTRACT_MSG =
            "Cannot publish research without an abstract";
    public static final String MISSING_COLLECTION_NAME_MSG =
            "Collection name is required";
    public static final String MISSING_FILENAME_MSG =
            "File name is required";
    public static final String MISSING_MEDIA_ID_MSG =
            "Media ID is required";
    public static final String MISSING_MEDIA_ID_DOWNLOAD_MSG =
            "mediaId is required — downloads are tracked per physical file (PDF/video/audio/zip).";
    public static final String MISSING_NEW_NAME_MSG =
            "New collection name is required";
    public static final String MISSING_OLD_NAME_MSG =
            "Old collection name is required";
    public static final String MISSING_RESEARCHER_ID_MSG =
            "Researcher ID is required";
    public static final String MISSING_RESEARCH_ID_MSG =
            "Research ID is required";
    public static final String MISSING_SLUG_MSG =
            "Slug is required";
    public static final String MISSING_SOURCE_ID_MSG =
            "Source ID is required";
    public static final String MISSING_TAGS_MSG =
            "At least one tag is required";
    public static final String MISSING_TITLE_PUBLISH_MSG =
            "Cannot publish research without a title";
    public static final String MISSING_TITLE_SLUG_MSG =
            "Title is required to generate slug";
    public static final String MISSING_TOKEN_MSG =
            "Share token is required";
    public static final String MISSING_USER_ID_MSG =
            "User ID is required";
    public static final String NOT_HIDDEN_MSG =
            "Comment is not hidden";
    public static final String NOT_PUBLISHED_RETRACT_MSG =
            "Only published research can be retracted";
    public static final String NOT_PUBLISHED_UNPUBLISH_MSG =
            "Research is not published";
    public static final String NOT_PUBLISHED_YET_MSG =
            "Research is not published yet";
    public static final String NULL_REQUEST_BODY_MSG =
            "Request body cannot be null";
    public static final String PARENT_DELETED_MSG =
            "Cannot reply to a deleted comment";
    public static final String SOURCE_MISMATCH_MSG =
            "Source does not belong to this research";
    public static final String SOURCE_UPLOAD_ERROR_MSG =
            "Failed to upload source file";
    public static final String STORAGE_UNAVAILABLE_MSG =
            "File storage service is currently unavailable. Please try again later.";
    public static final String STORAGE_NOT_CONFIGURED_MSG =
            "File storage service is not configured";
    public static final String TAG_FETCH_ERROR_MSG =
            "Failed to fetch trending tags";
    public static final String UNEXPECTED_ERROR_MSG =
            "An unexpected error occurred while creating the research";
    public static final String UPLOAD_TO_STORAGE_FAILED_MSG =
            "Failed to upload file to storage";
    public static final String URL_GENERATION_ERROR_DOWNLOAD_MSG =
            "Failed to generate download link";
    public static final String URL_GENERATION_ERROR_FILE_MSG =
            "Failed to generate file URL";
    public static final String VIDEO_UPLOAD_ERROR_MSG =
            "Failed to upload video promo";

    // forbidden-action copy (default ACCESS_FORBIDDEN code)
    public static final String MEDIA_NOT_IN_RESEARCH_MSG =
            "Media does not belong to this research";
    public static final String COMMENT_NOT_IN_RESEARCH_MSG =
            "Comment does not belong to this research";
    public static final String EDIT_OWN_COMMENTS_ONLY_MSG =
            "You can only edit your own comments";
    public static final String DELETE_OWN_COMMENTS_ONLY_MSG =
            "You can only delete your own comments or comments on your research";
    public static final String HIDE_OWN_COMMENTS_ONLY_MSG =
            "You can only hide your own comments or comments on your research";
    public static final String UNHIDE_OWN_COMMENTS_ONLY_MSG =
            "You can only unhide your own comments or comments on your research";
    public static final String ONLY_RESEARCHERS_MANAGE_MSG =
            "Only researchers can manage researches";
    public static final String NOT_RESEARCH_OWNER_MSG =
            "You do not own this research";

    // conflict copy (default RESOURCE_CONFLICT code)
    public static final String RESEARCH_MODIFIED_CONFLICT_MSG =
            "Research was modified by another user. Please refresh and try again.";
    public static final String REACTION_CONFLICT_MSG =
            "Reaction update conflict. Please retry.";
    public static final String CONTRIBUTOR_ALREADY_EXISTS_MSG =
            "User is already a contributor on this research";

    // ── validation copy (plain literals — annotation values) ────────────
    public static final String VAL_TITLE_REQUIRED           = "Title is required";
    public static final String VAL_TITLE_MAX_500            = "Title must not exceed 500 characters";
    public static final String VAL_DESCRIPTION_REQUIRED     = "Description is required";
    public static final String VAL_DESCRIPTION_MAX_50000    = "Description must not exceed 50 000 characters";
    public static final String VAL_ABSTRACT_REQUIRED        = "Abstract is required";
    public static final String VAL_ABSTRACT_MAX_5000        = "Abstract must not exceed 5 000 characters";
    public static final String VAL_KEYWORDS_MAX_2000        = "Keywords must not exceed 2 000 characters";
    public static final String VAL_CITATION_MAX_5000        = "Citation must not exceed 5 000 characters";
    public static final String VAL_TAGS_REQUIRED            = "At least one tag is required";
    public static final String VAL_TAGS_MAX_30              = "Maximum 30 tags allowed";
    public static final String VAL_CAPTION_MAX_500          = "Caption must not exceed 500 characters";
    public static final String VAL_ALT_TEXT_MAX_300         = "Alt text must not exceed 300 characters";
    public static final String VAL_ALT_TEXT_MAX_255         = "Alt text must not exceed 255 characters";
    public static final String VAL_USER_ID_REQUIRED         = "userId is required";
    public static final String VAL_CONTRIBUTION_NOTE_MAX_500 = "contributionNote must not exceed 500 characters";
    public static final String VAL_COMMENT_CONTENT_REQUIRED = "Comment content is required";
    public static final String VAL_COMMENT_MAX_5000         = "Comment must not exceed 5 000 characters";
    public static final String VAL_SOURCE_TYPE_REQUIRED     = "Source type is required";
    public static final String VAL_SOURCE_TITLE_REQUIRED    = "Source title is required";

    // ── notification copy ───────────────────────────────────────────────
    public static final String NOTIF_CONTRIBUTOR_ADDED_TITLE =
            "You were added to a research paper";
    public static final String NOTIF_CONTRIBUTOR_ADDED_BODY =
            "%s (@%s) added you as a %s on \"%s\".";
}
