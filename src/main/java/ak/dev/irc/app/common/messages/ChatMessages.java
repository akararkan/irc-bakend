package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the chat core module ({@code app/chat}):
 * conversations, messages, groups/members, invites, polls, message requests,
 * scheduled messages, drafts, stars and typing. Catalog:
 * docs/errors/user-facing-messages.md §1 {@code chat}* tables. Conventions:
 * see the {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class ChatMessages {

    private ChatMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String ACCESS_FORBIDDEN      = "ACCESS_FORBIDDEN";
    public static final String NOT_A_MEMBER          = "NOT_A_MEMBER";
    public static final String BLOCKED               = "BLOCKED";
    public static final String ADMINS_ONLY           = "ADMINS_ONLY";
    public static final String READ_ONLY             = "READ_ONLY";
    public static final String NOT_OWNER             = "NOT_OWNER";
    public static final String CANNOT_ACT_ON_ADMIN   = "CANNOT_ACT_ON_ADMIN";
    public static final String SUBSCRIBERS_HIDDEN    = "SUBSCRIBERS_HIDDEN";
    public static final String INVITE_INVALID        = "INVITE_INVALID";
    public static final String PROTECTED_CONTENT     = "PROTECTED_CONTENT";
    public static final String REACTIONS_DISABLED    = "REACTIONS_DISABLED";
    public static final String CHANNEL_FROZEN        = "CHANNEL_FROZEN";
    public static final String REQUEST_LIMIT_REACHED = "REQUEST_LIMIT_REACHED";

    // ── message text: auth ──────────────────────────────────────────────
    public static final String AUTH_REQUIRED_MSG =
            "Authentication required";
    /** Raw text/plain 401 body of the SSE stream endpoint (not the JSON envelope). */
    public static final String SSE_TOKEN_REQUIRED_MSG =
            "Authentication required. Pass access token as ?token=<jwt>.";

    // ── message text: membership / gates ────────────────────────────────
    public static final String NOT_A_MEMBER_MSG =
            "You are not a member of this conversation.";
    public static final String NOT_AN_ACTIVE_MEMBER_MSG =
            "You are not an active member of this conversation.";
    public static final String NOT_A_MEMBER_SHORT_MSG =
            "You are not a member.";
    public static final String NOT_TARGET_MEMBER_MSG =
            "You are not a member of the target.";
    public static final String SOURCE_MESSAGE_INACCESSIBLE_MSG =
            "You cannot access the source message.";
    public static final String RESTRICTED_POSTING_MSG =
            "You are restricted from posting here.";
    public static final String RESTRICTED_INTERACTING_MSG =
            "You are restricted from interacting here.";
    public static final String ADMINS_ONLY_SEND_MSG =
            "Only admins can send messages here.";
    public static final String CHANNEL_POST_RIGHT_MSG =
            "You do not have the right to post in this channel.";
    public static final String CHANNEL_FROZEN_MSG =
            "Posting in this channel has been suspended by platform moderation.";
    public static final String INTERACTION_NOT_ALLOWED_MSG =
            "This interaction is not allowed.";
    public static final String STRANGER_CAP_REACHED_MSG =
            "You've reached the limit before this request is accepted.";

    // ── message text: conversations / groups ────────────────────────────
    public static final String SELF_CONVERSATION_MSG =
            "You cannot start a conversation with yourself.";
    public static final String DIRECT_RECIPIENT_REQUIRED_MSG =
            "recipientId is required for a DIRECT conversation.";
    public static final String CONVERSATION_CREATE_FAILED_MSG =
            "Could not create the conversation.";
    public static final String DIRECT_NO_PEER_MSG =
            "Direct conversation has no peer.";
    public static final String GROUP_TITLE_REQUIRED_MSG =
            "A group requires a title.";
    public static final String GROUP_MAX_INITIAL_MEMBERS_MSG =
            "A group can start with at most %s members.";
    public static final String GROUP_ONLY_ACTION_MSG =
            "This action applies only to group conversations.";
    public static final String GROUP_OR_CHANNEL_ONLY_ACTION_MSG =
            "This action applies only to group or channel conversations.";
    public static final String EDIT_GROUP_INFO_FORBIDDEN_MSG =
            "You cannot edit this group's info.";
    public static final String CHANGE_GROUP_SETTINGS_FORBIDDEN_MSG =
            "You cannot change this group's settings.";
    public static final String SECONDS_NON_NEGATIVE_MSG =
            "seconds must be >= 0.";

    // ── message text: messages ──────────────────────────────────────────
    public static final String BODY_OR_FILE_REQUIRED_MSG =
            "Provide a body and/or at least one file.";
    public static final String EDIT_DELETED_MESSAGE_MSG =
            "Cannot edit a deleted message.";
    public static final String EDIT_SYSTEM_MESSAGE_MSG =
            "System messages cannot be edited.";
    public static final String EDIT_OWN_MESSAGES_ONLY_MSG =
            "You can only edit your own messages.";
    public static final String DELETE_MESSAGE_FORBIDDEN_MSG =
            "You cannot delete this message.";
    public static final String POLL_PAYLOAD_TYPE_MISMATCH_MSG =
            "A poll payload requires type POLL.";
    public static final String POLL_PAYLOAD_REQUIRED_MSG =
            "A POLL message requires a poll payload.";
    public static final String TYPED_PAYLOAD_REQUIRED_MSG =
            "A %s message requires a %s payload.";
    public static final String TYPED_PAYLOAD_MISMATCH_MSG =
            "A %s payload requires type %s.";
    public static final String TYPED_PAYLOAD_STORE_FAILED_MSG =
            "Could not store the %s payload.";
    public static final String PROTECTED_CONTENT_MSG =
            "This channel's content is protected and cannot be forwarded.";

    // ── message text: pin / reactions ───────────────────────────────────
    public static final String PIN_GROUP_FORBIDDEN_MSG =
            "You cannot pin messages in this group.";
    public static final String PIN_CHANNEL_FORBIDDEN_MSG =
            "You cannot pin posts in this channel.";
    public static final String REACTIONS_DISABLED_MSG =
            "Reactions are disabled in this channel.";
    public static final String REACTION_NOT_ALLOWED_MSG =
            "This reaction is not allowed in this channel.";

    // ── message text: polls ─────────────────────────────────────────────
    public static final String QUIZ_MULTIPLE_ANSWERS_MSG =
            "A quiz cannot allow multiple answers.";
    public static final String QUIZ_CORRECT_OPTION_REQUIRED_MSG =
            "A quiz requires a valid correctOptionIndex.";
    public static final String CORRECT_OPTION_QUIZ_ONLY_MSG =
            "correctOptionIndex applies only to quizzes.";
    public static final String POLL_CLOSED_MSG =
            "This poll is closed.";
    public static final String POLL_OPTION_OUT_OF_RANGE_MSG =
            "Option index out of range.";
    public static final String POLL_SINGLE_ANSWER_MSG =
            "This poll allows only one answer.";
    public static final String QUIZ_ANSWERS_FINAL_MSG =
            "Quiz answers are final.";
    public static final String POLL_CLOSE_FORBIDDEN_MSG =
            "You cannot close this poll.";
    public static final String NOT_A_POLL_MSG =
            "This message is not a poll.";
    public static final String POLL_STORE_FAILED_MSG =
            "Could not store the poll payload.";
    public static final String POLL_CORRUPT_MSG =
            "Corrupt poll payload.";

    // ── message text: members / roles ───────────────────────────────────
    public static final String ADD_MEMBERS_FORBIDDEN_MSG =
            "You cannot add members to this group.";
    public static final String ADD_SUBSCRIBERS_FORBIDDEN_MSG =
            "You cannot add subscribers to this channel.";
    public static final String SUBSCRIBERS_HIDDEN_MSG =
            "This channel's subscriber list is hidden.";
    public static final String OWNER_NOT_REMOVABLE_MSG =
            "The owner cannot be removed.";
    public static final String REMOVE_MEMBER_FORBIDDEN_MSG =
            "You cannot remove this member.";
    public static final String ROLE_MUST_BE_ADMIN_OR_MEMBER_MSG =
            "role must be ADMIN or MEMBER.";
    public static final String OWNER_ROLE_IMMUTABLE_MSG =
            "The owner's role cannot be changed.";
    public static final String CHANGE_ROLE_FORBIDDEN_MSG =
            "You cannot change this member's role.";
    public static final String OWNER_NOT_RESTRICTABLE_MSG =
            "The owner cannot be restricted.";
    public static final String RESTRICT_MEMBER_FORBIDDEN_MSG =
            "You cannot restrict this member.";
    public static final String LEAVE_TRANSFER_FIRST_MSG =
            "Transfer ownership before leaving, or delete the group.";
    public static final String TRANSFER_OWNER_ONLY_MSG =
            "Only the owner can transfer ownership.";

    // ── message text: invites ───────────────────────────────────────────
    public static final String INVITE_INVALID_MSG =
            "This invite link is invalid or has expired.";
    public static final String MANAGE_INVITES_FORBIDDEN_MSG =
            "You cannot manage invite links here.";
    public static final String INVITE_TOKEN_FAILED_MSG =
            "Could not process the invite token.";

    // ── message text: message requests / scheduled ──────────────────────
    public static final String REQUEST_NOT_ADDRESSED_MSG =
            "This request is not addressed to you.";
    public static final String SCHEDULED_AT_FUTURE_MSG =
            "scheduledAt must be in the future.";
    public static final String SCHEDULED_NOT_YOURS_MSG =
            "This scheduled message is not yours.";

    // ── validation copy (plain literals — annotation values) ────────────
    public static final String VAL_SCHEDULED_AT_REQUIRED       = "scheduledAt is required";
    public static final String VAL_SCHEDULED_AT_FUTURE         = "scheduledAt must be in the future";
    public static final String VAL_CLIENT_NONCE_REQUIRED       = "clientNonce is required";
    public static final String VAL_CLIENT_NONCE_IDEMPOTENT     = "clientNonce is required for idempotent send";
    public static final String VAL_TYPE_REQUIRED               = "type is required";
    public static final String VAL_BODY_REQUIRED               = "body is required";
    public static final String VAL_MESSAGE_MAX                 = "a message may not exceed 8000 characters";
    public static final String VAL_ATTACHMENTS_MAX             = "at most 10 attachments per message";
    public static final String VAL_CONVERSATION_TYPE_REQUIRED  = "type is required (DIRECT | GROUP)";
    public static final String VAL_GROUP_TITLE_MAX             = "group title must not exceed 120 characters";
    public static final String VAL_GROUP_DESCRIPTION_MAX       = "group description must not exceed 500 characters";
    public static final String VAL_GROUP_MEMBERS_MAX           = "a group can start with at most 256 members";
    public static final String VAL_USER_IDS_REQUIRED           = "userIds must not be empty";
    public static final String VAL_ADD_MEMBERS_MAX             = "add at most 100 members per request";
    public static final String VAL_POLL_QUESTION_REQUIRED      = "a poll requires a question";
    public static final String VAL_POLL_OPTIONS_RANGE          = "a poll needs 2–10 options";
    public static final String VAL_POLL_VOTE_PICK_ONE          = "pick at least one option";
    public static final String VAL_SECONDS_NON_NEGATIVE        = "seconds must be >= 0";
    public static final String VAL_TOKEN_REQUIRED              = "token is required";
    public static final String VAL_MEDIA_KIND_REQUIRED         = "media kind is required";
    public static final String VAL_STORAGE_KEY_REQUIRED        = "storageKey is required";
    public static final String VAL_EXPIRES_IN_HOURS_POSITIVE   = "expiresInHours must be positive";
    public static final String VAL_MAX_USES_POSITIVE           = "maxUses must be positive";
    public static final String VAL_VIEWS_BATCH_MAX             = "at most 100 posts per batch";
    public static final String VAL_ROLE_REQUIRED               = "role is required (ADMIN | MEMBER)";
    public static final String VAL_LAST_READ_REQUIRED          = "lastReadMessageId is required";
    public static final String VAL_NEW_OWNER_REQUIRED          = "newOwnerId is required";
    public static final String VAL_EMOJI_REQUIRED              = "emoji is required";
    public static final String VAL_EMOJI_SINGLE_GRAPHEME       = "emoji must be a single grapheme";
    public static final String VAL_TARGET_CONVERSATION_REQUIRED = "targetConversationId is required";

    // ── notification copy ───────────────────────────────────────────────
    public static final String NOTIF_NEW_MESSAGE_TITLE     = "New message";
    public static final String NOTIF_MESSAGE_REQUEST_TITLE = "Message request";
    public static final String NOTIF_MESSAGE_REQUEST_BODY  = "%s wants to send you a message";
    public static final String NOTIF_ADDED_TO_GROUP_TITLE  = "Added to a group";
    public static final String NOTIF_MISSED_CALL_TITLE     = "Missed call";
    public static final String NOTIF_MENTIONED_TITLE       = "You were mentioned";
    public static final String NOTIF_CHANNEL_POST_TITLE    = "New channel post";
    public static final String NOTIF_JOIN_REQUEST_TITLE    = "Join request";
    public static final String NOTIF_JOIN_APPROVED_TITLE   = "Request approved";
}
