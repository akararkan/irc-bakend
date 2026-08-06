package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the chat live layer — broadcast channels,
 * voice/video calls, live streams, the multi-guest stage, gifts and
 * recordings ({@code app/chat} — Channel/Call/Stream/Stage/Gift services and
 * controllers). Catalog: docs/errors/user-facing-messages.md §1.62–§1.85
 * {@code chat} sections. Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class ChannelStreamMessages {

    private ChannelStreamMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String ACCESS_FORBIDDEN = "ACCESS_FORBIDDEN";
    public static final String ADMINS_ONLY      = "ADMINS_ONLY";
    public static final String BLOCKED          = "BLOCKED";
    public static final String NOT_A_MEMBER     = "NOT_A_MEMBER";
    public static final String NOT_OWNER        = "NOT_OWNER";
    public static final String READ_ONLY        = "READ_ONLY";
    public static final String STREAM_NOT_LIVE  = "STREAM_NOT_LIVE";

    // ── message text: auth guard (controllers' requireId) ───────────────
    public static final String AUTH_REQUIRED_MSG =
            "Authentication required";

    // ── message text: channels ──────────────────────────────────────────
    public static final String CHANNEL_TITLE_REQUIRED_MSG =
            "A channel requires a title.";
    public static final String TITLE_BLANK_MSG =
            "Title cannot be blank.";
    public static final String CHANNEL_HANDLE_REQUIRED_MSG =
            "A public channel requires a @handle.";
    public static final String CHANNEL_HANDLE_TAKEN_MSG =
            "That @handle is already taken.";
    public static final String CHANNEL_HANDLE_INVALID_MSG =
            "Handle must be 3–32 characters of a–z, 0–9 or underscore.";
    public static final String CHANNEL_EDIT_INFO_FORBIDDEN_MSG =
            "You cannot edit this channel's info.";
    public static final String CHANNEL_MANAGE_ADMINS_FORBIDDEN_MSG =
            "You cannot manage admins in this channel.";
    public static final String CHANNEL_IMAGE_FILE_REQUIRED_MSG =
            "Provide an image file.";
    public static final String OWNER_RIGHTS_IMMUTABLE_MSG =
            "The owner's rights cannot be edited.";
    public static final String OWNER_NOT_DEMOTABLE_MSG =
            "The owner cannot be demoted.";
    public static final String CHANNEL_PRIVATE_MSG =
            "This channel is private.";
    public static final String OWNER_CANNOT_UNSUBSCRIBE_MSG =
            "The owner cannot unsubscribe from their own channel.";
    public static final String NOT_CHANNEL_MEMBER_MSG =
            "You are not a member of this channel.";
    public static final String CHANNEL_STATS_ADMINS_ONLY_MSG =
            "Only channel admins can view statistics.";

    // ── message text: discussion groups ─────────────────────────────────
    public static final String DISCUSSION_LINK_ADMIN_REQUIRED_MSG =
            "You must be an admin of the discussion group to link it.";
    public static final String DISCUSSION_GROUP_TAKEN_MSG =
            "That group is already another channel's discussion group.";
    public static final String DISCUSSION_GROUP_MISSING_MSG =
            "This channel has no discussion group — comments are disabled.";
    public static final String DISCUSSION_GROUP_GONE_MSG =
            "The discussion group is gone.";
    public static final String DISCUSSION_RESTRICTED_MSG =
            "You are restricted in the discussion group.";
    public static final String NOT_CHANNEL_POST_MSG =
            "That message is not a post of this channel.";
    public static final String DISCUSSION_MANAGE_FORBIDDEN_MSG =
            "You cannot manage this channel's discussion group.";

    // ── message text: join requests ─────────────────────────────────────
    public static final String JOIN_REQUEST_DECIDED_MSG =
            "This join request was already decided.";
    public static final String JOIN_REQUESTS_MANAGE_FORBIDDEN_MSG =
            "You cannot manage join requests here.";

    // ── message text: calls ─────────────────────────────────────────────
    public static final String CALL_BLOCKED_MSG =
            "This interaction is not allowed.";
    public static final String CALL_TARGET_NOT_IN_CALL_MSG =
            "Target is not part of this call.";
    public static final String CALL_NOT_ACTIVE_MSG =
            "This call is no longer active.";
    public static final String NOT_IN_CALL_MSG =
            "You are not part of this call.";
    public static final String NOT_ACTIVE_CONVERSATION_MEMBER_MSG =
            "You are not an active member of this conversation.";

    // ── message text: live streams / recordings ─────────────────────────
    public static final String STREAM_TITLE_REQUIRED_MSG =
            "A stream requires a title.";
    public static final String STREAM_END_HOST_ONLY_MSG =
            "Only the host can end this stream.";
    public static final String STREAM_MANAGE_HOST_ONLY_MSG =
            "Only the host can manage this stream.";
    public static final String RECORDING_CONTROL_HOST_ONLY_MSG =
            "Only the host can control this recording.";
    public static final String STREAM_NOT_LIVE_MSG =
            "This stream is not live.";
    public static final String STREAM_CHAT_TEXT_REQUIRED_MSG =
            "Message text is required.";
    public static final String STREAM_CHAT_JOIN_FIRST_MSG =
            "Join the stream before chatting.";

    // ── message text: multi-guest stage ─────────────────────────────────
    public static final String STAGE_HOST_SELF_MSG =
            "You are the host of this stream.";
    public static final String STAGE_JOIN_BEFORE_REQUEST_MSG =
            "Join the stream before asking to come up.";
    public static final String STAGE_ALREADY_ON_MSG =
            "You are already on stage.";
    public static final String STAGE_NO_PENDING_INVITE_MSG =
            "You have no pending invite to come up.";
    public static final String STAGE_NO_PENDING_REQUEST_MSG =
            "No pending request from this user.";
    public static final String STAGE_TARGET_ALREADY_ON_MSG =
            "They are already on stage.";
    public static final String STAGE_TARGET_NOT_ON_MSG =
            "That user is not on stage.";
    public static final String STAGE_FULL_MSG =
            "The stage is full (%s guests).";
    public static final String STAGE_MANAGE_HOST_ONLY_MSG =
            "Only the host can manage this stream's stage.";
    public static final String STAGE_JOIN_STREAM_FIRST_MSG =
            "Join the stream first.";

    // ── message text: gifts ─────────────────────────────────────────────
    public static final String GIFT_ID_REQUIRED_MSG =
            "A gift id is required.";
    public static final String GIFT_UNKNOWN_MSG =
            "Unknown gift '%s'.";

    // ── validation copy (plain literals — annotation values) ────────────
    public static final String VAL_CHANNEL_TITLE_MAX =
            "channel title must not exceed 120 characters";
    public static final String VAL_CHANNEL_DESCRIPTION_MAX =
            "channel description must not exceed 500 characters";
    public static final String VAL_MEDIA_KIND_REQUIRED =
            "media kind is required";
    public static final String VAL_STORAGE_KEY_REQUIRED =
            "storageKey is required";

    // ── notification copy ───────────────────────────────────────────────
    /** Title of the STREAM_STARTED follower notification; %s = host label (e.g. "@alice"). */
    public static final String NOTIF_STREAM_STARTED_TITLE =
            "%s is live";
    /** Body fallback when the stream has no title. */
    public static final String NOTIF_STREAM_STARTED_BODY_FALLBACK =
            "Tap to watch the stream.";
}
