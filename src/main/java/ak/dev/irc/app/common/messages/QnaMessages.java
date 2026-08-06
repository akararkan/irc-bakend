package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the Q&amp;A module ({@code app/qna}).
 * Catalog: docs/errors/user-facing-messages.md §1 `qna` (+ `qna (cross-cutting)`).
 * Conventions: see {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class QnaMessages {

    private QnaMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String EMPTY_TITLE              = "EMPTY_TITLE";
    public static final String EMPTY_BODY               = "EMPTY_BODY";
    public static final String EMPTY_ANSWER             = "EMPTY_ANSWER";
    public static final String QUESTION_CLOSED          = "QUESTION_CLOSED";
    public static final String ANSWERS_LOCKED           = "ANSWERS_LOCKED";
    public static final String ANSWER_LIMIT_REACHED     = "ANSWER_LIMIT_REACHED";
    public static final String REANSWER_NOT_ACCEPTABLE  = "REANSWER_NOT_ACCEPTABLE";
    public static final String ATTACHMENT_MISMATCH      = "ATTACHMENT_MISMATCH";
    public static final String SOURCE_MISMATCH          = "SOURCE_MISMATCH";
    public static final String MISSING_FILE             = "MISSING_FILE";
    public static final String MISSING_COLLECTION_NAME  = "MISSING_COLLECTION_NAME";
    public static final String MISSING_OLD_NAME         = "MISSING_OLD_NAME";
    public static final String MISSING_NEW_NAME         = "MISSING_NEW_NAME";

    // Block-edge codes passed to SocialGuard.requireNotBlockedBetween — the
    // message ("This interaction is not allowed.") lives in SocialGuard.
    public static final String ANSWER_BLOCKED_RELATIONSHIP          = "ANSWER_BLOCKED_RELATIONSHIP";
    public static final String REANSWER_BLOCKED_RELATIONSHIP        = "REANSWER_BLOCKED_RELATIONSHIP";
    public static final String ANSWER_REACTION_BLOCKED_RELATIONSHIP = "ANSWER_REACTION_BLOCKED_RELATIONSHIP";
    public static final String QNA_SAVE_BLOCKED_RELATIONSHIP        = "QNA_SAVE_BLOCKED_RELATIONSHIP";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String EMPTY_TITLE_MSG =
            "Question title cannot be empty";
    /** Source-edit variant — shares the {@link #EMPTY_TITLE} code. */
    public static final String EMPTY_TITLE_SOURCE_MSG =
            "Source title cannot be empty";
    public static final String EMPTY_BODY_MSG =
            "Question body cannot be empty";
    public static final String EMPTY_ANSWER_MSG =
            "Answer body cannot be empty";
    public static final String QUESTION_CLOSED_MSG =
            "Question is closed";
    public static final String ANSWERS_LOCKED_MSG =
            "Answers are locked for this question";
    public static final String ANSWER_LIMIT_REACHED_MSG =
            "Maximum number of answers (%s) reached";
    public static final String REANSWER_NOT_ACCEPTABLE_MSG =
            "Reanswers cannot be accepted as best answer";
    public static final String ATTACHMENT_MISMATCH_MSG =
            "Attachment does not belong to this answer";
    public static final String SOURCE_MISMATCH_MSG =
            "Source does not belong to this answer";
    public static final String MISSING_FILE_MSG =
            "File is required";
    public static final String MISSING_COLLECTION_NAME_MSG =
            "Collection name is required";
    public static final String MISSING_OLD_NAME_MSG =
            "Old collection name is required";
    public static final String MISSING_NEW_NAME_MSG =
            "New collection name is required";

    // ── 401 (code defaults to AUTH_UNAUTHORIZED in the exception) ───────
    public static final String AUTH_REQUIRED_MSG =
            "Authentication required";

    // ── 403 (code defaults to ACCESS_FORBIDDEN in the exception) ────────
    public static final String EDIT_OWN_QUESTION_MSG =
            "You can only edit your own question";
    public static final String EDIT_OWN_ANSWER_MSG =
            "You can only edit your own answer or answers on your question";
    public static final String DELETE_OWN_QUESTION_MSG =
            "You can only delete your own question";
    public static final String DELETE_OWN_ANSWER_MSG =
            "You can only delete your own answer or answers on your question";
    public static final String CLOSE_QUESTION_FORBIDDEN_MSG =
            "You cannot close this question";
    public static final String REOPEN_QUESTION_FORBIDDEN_MSG =
            "You cannot reopen this question";
    public static final String ARCHIVE_QUESTION_FORBIDDEN_MSG =
            "You cannot archive this question";
    public static final String LOCK_ANSWERS_AUTHOR_ONLY_MSG =
            "Only the question author can lock answers";
    public static final String UNLOCK_ANSWERS_AUTHOR_ONLY_MSG =
            "Only the question author can unlock answers";
    public static final String ANSWER_LIMIT_AUTHOR_ONLY_MSG =
            "Only the question author can set the answer limit";
    public static final String ACCEPT_AUTHOR_ONLY_MSG =
            "Only the question author can accept answers";
    public static final String UNACCEPT_AUTHOR_ONLY_MSG =
            "Only the question author can unaccept answers";
    public static final String ATTACHMENT_UPLOAD_OWN_ANSWER_MSG =
            "You can only upload attachments to your own answer";
    public static final String ATTACHMENT_EDIT_OWN_ANSWER_MSG =
            "You can only edit attachments on your own answer";
    public static final String ATTACHMENT_DELETE_OWN_ANSWER_MSG =
            "You can only delete attachments from your own answer";
    public static final String SOURCE_ADD_OWN_ANSWER_MSG =
            "You can only add sources to your own answer";
    public static final String SOURCE_FILE_OWN_ANSWER_MSG =
            "You can only attach files to sources on your own answer";
    public static final String SOURCE_EDIT_OWN_ANSWER_MSG =
            "You can only edit sources on your own answer";
    public static final String SOURCE_DELETE_OWN_ANSWER_MSG =
            "You can only delete sources from your own answer";
    public static final String ONLY_SCHOLARS_POST_MSG =
            "Only scholars can post questions";
    public static final String ONLY_SCHOLARS_RESEARCHERS_ANSWER_MSG =
            "Only scholars and researchers can answer questions";

    // ── validation copy (plain literals — annotation values must be
    //    compile-time constants, never templates) ────────────────────────
    public static final String VAL_QUESTION_TITLE_REQUIRED =
            "Question title is required";
    public static final String VAL_QUESTION_TITLE_MAX =
            "Question title must not exceed 500 characters";
    public static final String VAL_QUESTION_BODY_REQUIRED =
            "Question body is required";
    public static final String VAL_QUESTION_BODY_MAX =
            "Question body must not exceed 10000 characters";
    public static final String VAL_QUESTION_TAGS_MAX =
            "A question can have at most 30 tags";
    public static final String VAL_QUESTION_KEYWORDS_MAX =
            "Keywords must not exceed 2000 characters";
    public static final String VAL_ANSWER_BODY_REQUIRED =
            "Answer body is required";
    public static final String VAL_ANSWER_MAX_CREATE =
            "Answer must not exceed 10000 characters";
    public static final String VAL_ANSWER_MAX_EDIT =
            "Answer must not exceed 5000 characters";
    public static final String VAL_SOURCE_TYPE_REQUIRED =
            "Source type is required";
    public static final String VAL_SOURCE_TITLE_REQUIRED =
            "Source title is required";
    public static final String VAL_SOURCE_TITLE_MAX =
            "Source title must not exceed 500 characters";
    public static final String VAL_SOURCE_CITATION_MAX =
            "Citation text must not exceed 5000 characters";
    public static final String VAL_SOURCE_ISBN_MAX =
            "ISBN must not exceed 20 characters";
    public static final String VAL_ATTACHMENT_CAPTION_MAX =
            "Caption must not exceed 500 characters";
}
