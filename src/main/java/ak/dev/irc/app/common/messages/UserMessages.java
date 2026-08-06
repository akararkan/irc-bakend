package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the user / activity / notification-consumer
 * modules ({@code app/user}, {@code app/activity}, {@code app/rabbitmq}).
 * Catalog: docs/errors/user-facing-messages.md §1 {@code user*} + {@code activity}
 * tables, §2 notes, §3 in-app notifications. Auth-flow copy lives in
 * {@link AuthMessages}. Follows the registry conventions — see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class UserMessages {

    private UserMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String EMPTY_FILE                  = "EMPTY_FILE";
    public static final String INVALID_FILE_TYPE           = "INVALID_FILE_TYPE";
    public static final String MISSING_FILENAME            = "MISSING_FILENAME";
    public static final String INVALID_FILENAME            = "INVALID_FILENAME";
    public static final String INVALID_LANGUAGE            = "INVALID_LANGUAGE";
    public static final String FOLLOW_BLOCKED_RELATIONSHIP = "FOLLOW_BLOCKED_RELATIONSHIP";
    public static final String FOLLOW_PROFILE_LOCKED       = "FOLLOW_PROFILE_LOCKED";
    public static final String RESTRICT_ALREADY_BLOCKED    = "RESTRICT_ALREADY_BLOCKED";
    public static final String SELF_ACTION_NOT_ALLOWED     = "SELF_ACTION_NOT_ALLOWED";
    public static final String SELF_ACTION                 = "SELF_ACTION";
    public static final String SELF_ACTION_FORBIDDEN       = "SELF_ACTION_FORBIDDEN";
    public static final String INVALID_STATUS_FILTER       = "INVALID_STATUS_FILTER";
    public static final String INVALID_INPUT               = "INVALID_INPUT";
    public static final String LAST_ADMIN                  = "LAST_ADMIN";
    public static final String PASSWORD_OR_INVITE_REQUIRED = "PASSWORD_OR_INVITE_REQUIRED";
    public static final String INVITE_USED                 = "INVITE_USED";
    public static final String INVITE_INVALID              = "INVITE_INVALID";
    public static final String INVALID_BULK_ACTION         = "INVALID_BULK_ACTION";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String EMPTY_FILE_MSG =
            "Image file is required.";
    /** UserServiceImpl profile-image variant of EMPTY_FILE. */
    public static final String EMPTY_FILE_PROFILE_MSG =
            "Profile image file is required and cannot be empty.";
    public static final String INVALID_FILE_TYPE_MSG =
            "Invalid image type. Allowed: jpeg, png, webp, gif.";
    public static final String MISSING_FILENAME_MSG =
            "File name is required.";
    public static final String INVALID_FILENAME_MSG =
            "Invalid file name.";
    public static final String INVALID_LANGUAGE_MSG =
            "Invalid language code: %s";
    public static final String FOLLOW_BLOCKED_RELATIONSHIP_MSG =
            "Cannot follow this user due to an existing block relationship.";
    public static final String FOLLOW_PROFILE_LOCKED_MSG =
            "This user's profile is locked and cannot be followed.";
    public static final String NOT_FOLLOWING_MSG =
            "You are not following this user. Cannot unfollow.";
    public static final String ALREADY_BLOCKING_MSG =
            "You are already blocking this user.";
    public static final String NOT_BLOCKING_MSG =
            "You have not blocked this user. Cannot unblock.";
    public static final String RESTRICT_ALREADY_BLOCKED_MSG =
            "This user is already blocked. A restriction is unnecessary.";
    public static final String ALREADY_RESTRICTING_MSG =
            "You are already restricting this user.";
    public static final String NOT_RESTRICTING_MSG =
            "You are not restricting this user. Cannot unrestrict.";
    public static final String SELF_ACTION_NOT_ALLOWED_MSG =
            "You cannot %s yourself.";
    public static final String SELF_ACTION_MSG =
            "Cannot add yourself to close friends.";
    public static final String CLOSE_FRIEND_DUPLICATE_MSG =
            "User is already a close friend.";
    public static final String CLOSE_FRIEND_NOT_FOUND_MSG =
            "Close friend not found.";
    public static final String SELF_ACTION_FORBIDDEN_MSG =
            "You cannot %s.";
    public static final String INVALID_STATUS_FILTER_MSG =
            "Unknown status filter. Allowed: ACTIVE, DISABLED, DELETED.";
    public static final String INVALID_STATUS_FILTER_WITH_LOCKED_MSG =
            "Unknown status filter. Allowed: ACTIVE, DISABLED, DELETED, LOCKED.";
    public static final String ROLE_REQUIRED_MSG =
            "role is required";
    public static final String ROLE_REQUIRED_FOR_CHANGE_ROLE_MSG =
            "role is required for CHANGE_ROLE";
    public static final String LAST_ADMIN_MSG =
            "Cannot demote the last ADMIN.";
    public static final String PASSWORD_OR_INVITE_REQUIRED_MSG =
            "Provide temporaryPassword or set sendInvite=true — otherwise the account has no way to log in.";
    public static final String INVITE_USED_MSG =
            "Invite already used.";
    public static final String INVITE_INVALID_MSG =
            "Invite is invalid, revoked, used, or expired.";
    public static final String INVALID_BULK_ACTION_MSG =
            "Unknown action. Allowed: DISABLE, ENABLE, LOCK, UNLOCK, REQUEST_DELETION, CHANGE_ROLE.";
    public static final String MUST_BE_AUTHENTICATED_MSG =
            "You must be authenticated to perform this action.";
    public static final String MUST_BE_AUTHENTICATED_BRIEF_MSG =
            "You must be authenticated.";
    public static final String MUST_BE_AUTHENTICATED_NOTIFICATIONS_MSG =
            "You must be authenticated to access notifications.";
    public static final String AUTHENTICATION_REQUIRED_MSG =
            "Authentication required";
    public static final String ACTIVITY_DELETE_FORBIDDEN_MSG =
            "You cannot delete another user's activity";

    // ── validation copy (plain literals — annotation values) ────────────
    public static final String VAL_USERNAME_PATTERN =
            "Username may only contain letters, digits, dots, hyphens, and underscores";
    public static final String VAL_ROLE_REQUIRED   = "role is required";
    public static final String VAL_REASON_MAX_500  = "reason must not exceed 500 characters";

    // ── notes / warnings inside non-error responses ─────────────────────
    /** Plain-text 401 body written directly on SSE endpoints (text/event-stream). */
    public static final String NOTE_SSE_AUTH_REQUIRED =
            "Authentication required. Pass access token as ?token=<jwt>.";

    // ── notification copy (titles / bodies; templates via .formatted) ───
    public static final String NOTIF_NEW_FOLLOWER_TITLE = "New follower";
    public static final String NOTIF_NEW_FOLLOWER_BODY  = "%s (@%s) started following you.";
    public static final String NOTIF_UNBLOCKED_TITLE    = "You have been unblocked";
    public static final String NOTIF_UNBLOCKED_BODY     = "%s (@%s) unblocked you.";

    public static final String NOTIF_ACCOUNT_DISABLED_TITLE = "Your account has been disabled";
    public static final String NOTIF_ACCOUNT_DISABLED_BODY  =
            "Your account was disabled by an administrator. Contact support if you believe this is a mistake.";
    public static final String NOTIF_ACCOUNT_ENABLED_TITLE  = "Your account has been re-enabled";
    public static final String NOTIF_ACCOUNT_ENABLED_BODY   =
            "Your account was re-enabled by an administrator. You can log in again.";
    public static final String NOTIF_PASSWORD_RESET_TITLE   = "Your password was reset";
    public static final String NOTIF_PASSWORD_RESET_BODY    =
            "An administrator reset your password and signed out all devices. "
                    + "If you did not expect this, contact support immediately.";
    public static final String NOTIF_TWO_FA_RESET_TITLE     = "Two-factor authentication was reset";
    public static final String NOTIF_TWO_FA_RESET_BODY      =
            "An administrator reset the two-factor authentication on your account. "
                    + "If you did not request this, contact support immediately.";
    public static final String NOTIF_STRIKE_TITLE           = "Account warning";
    public static final String NOTIF_STRIKE_BODY            =
            "A moderation strike was recorded on your account: %s. Strikes expire automatically after 90 days.";

    public static final String NOTIF_INVITE_SUBJECT             = "You have been invited to IRC";
    public static final String NOTIF_INVITE_RESEND_SUBJECT      = "Your IRC invitation (reminder)";
    public static final String NOTIF_INVITE_ADMIN_CREATED_INTRO =
            "An administrator created an account for you. Set your password to get started: ";
    public static final String NOTIF_INVITE_JOIN_INTRO          =
            "You have been invited to join IRC. Create your account here: ";

    public static final String NOTIF_RESEARCH_PUBLISHED_TITLE = "New research published";
    public static final String NOTIF_RESEARCH_PUBLISHED_BODY  = "%s (@%s) published: \"%s\"";
    public static final String NOTIF_PUBLICATION_LIKED_TITLE  = "Someone reacted to your research";
    public static final String NOTIF_PUBLICATION_LIKED_BODY   = "%s (@%s) reacted %s to: \"%s\"";
    public static final String NOTIF_PUBLICATION_COMMENTED_TITLE = "New comment on your research";
    public static final String NOTIF_PUBLICATION_COMMENTED_BODY  = "%s (@%s) commented: \"%s\"";
    public static final String NOTIF_PUBLICATION_COMMENT_REACTED_TITLE = "Someone reacted to your comment";
    public static final String NOTIF_PUBLICATION_COMMENT_REACTED_BODY  =
            "%s (@%s) reacted to your comment on: \"%s\"";

    public static final String NOTIF_QUESTION_NEW_SCHOLAR_TITLE  = "New question to answer";
    public static final String NOTIF_QUESTION_NEW_FOLLOWER_TITLE = "New question from %s";
    public static final String NOTIF_QUESTION_NEW_BODY           = "%s (@%s) asked: \"%s\"";
    public static final String NOTIF_QUESTION_ANSWERED_TITLE     = "Your question has an answer";
    public static final String NOTIF_QUESTION_ANSWERED_BODY      = "%s (@%s) answered: \"%s\"";
    public static final String NOTIF_ANSWER_REACTED_TITLE        = "Someone reacted to your answer";
    public static final String NOTIF_ANSWER_ACCEPTED_TITLE       = "Your answer was accepted as best";
    public static final String NOTIF_ANSWER_ACCEPTED_BODY        =
            "%s (@%s) accepted your answer on: \"%s\"";

    public static final String NOTIF_POST_NEW_TITLE             = "New %s from %s";
    public static final String NOTIF_POST_NEW_BODY              = "%s (@%s) posted a new %s.";
    public static final String NOTIF_POST_REACTED_TITLE         = "Someone reacted to your post";
    public static final String NOTIF_POST_REACTED_BODY          = "%s (@%s) reacted %s to your post.";
    public static final String NOTIF_POST_COMMENTED_TITLE       = "New comment on your post";
    public static final String NOTIF_POST_COMMENT_REPLIED_TITLE = "New reply on your comment";
    public static final String NOTIF_POST_COMMENT_REACTED_TITLE = "Someone reacted to your comment";
    public static final String NOTIF_POST_COMMENT_REACTED_BODY  = "%s (@%s) reacted %s to your comment.";
    public static final String NOTIF_POST_SHARED_TITLE          = "Someone shared your post";
    public static final String NOTIF_POST_SHARED_BODY           = "%s (@%s) shared your post.";

    public static final String NOTIF_USER_MENTIONED_TITLE = "You were mentioned";

    public static final String NOTIF_SOUND_APPROVED_TITLE = "Your sound was approved";
    public static final String NOTIF_SOUND_APPROVED_BODY  = "\"%s\" is now live in the sound library.";
    public static final String NOTIF_SOUND_REJECTED_TITLE = "Your sound was not approved";
}
