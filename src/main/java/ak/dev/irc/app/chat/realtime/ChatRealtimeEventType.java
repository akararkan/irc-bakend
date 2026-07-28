package ak.dev.irc.app.chat.realtime;

/**
 * Every event multiplexed over the single per-user chat SSE stream. The
 * {@link #wire()} value is the SSE {@code event:} name the browser subscribes to.
 * One stream carries all of a user's conversations; the client fans them out
 * locally by {@code conversationId}.
 */
public enum ChatRealtimeEventType {

    MESSAGE_NEW("message.new"),
    MESSAGE_EDITED("message.edited"),
    MESSAGE_DELETED("message.deleted"),
    MESSAGE_REACTION("message.reaction"),
    RECEIPT_READ("receipt.read"),
    RECEIPT_DELIVERED("receipt.delivered"),
    TYPING("typing"),
    PRESENCE("presence"),
    CONVERSATION_UPDATED("conversation.updated"),
    MEMBER_CHANGED("member.changed"),
    REQUEST_NEW("request.new"),

    // Channels
    POLL_UPDATED("poll.updated"),
    JOIN_REQUEST_NEW("channel.join_request"),
    /** A discussion-group comment was added (+1) / removed (−1) on a channel
     *  post — {@code messageId} is the POST id; delta-not-counts. */
    MESSAGE_COMMENT("message.comment"),

    // Calls (voice/video signaling)
    CALL_INCOMING("call.incoming"),
    CALL_ACCEPTED("call.accepted"),
    CALL_DECLINED("call.declined"),
    CALL_ENDED("call.ended"),
    CALL_PARTICIPANT("call.participant"),
    CALL_SIGNAL("call.signal"),

    // Live streaming
    STREAM_STARTED("stream.started"),
    STREAM_ENDED("stream.ended"),
    STREAM_UPDATED("stream.updated"),
    STREAM_VIEWER("stream.viewer"),
    STREAM_CHAT("stream.chat"),

    // Live streaming — multi-guest "stage", reactions & gifts
    /** The stage roster changed (guest up/down/muted). Carries {@code stage};
     *  delivered to everyone watching + the host. */
    STREAM_STAGE("stream.stage"),
    /** A viewer raised their hand to come up. Carries {@code stageMember} (the
     *  requester); delivered to the host only. */
    STREAM_STAGE_REQUEST("stream.stage.request"),
    /** The host invited a viewer up. Carries {@code stageMember} (the host who
     *  invited, so the client can say "@host invited you up"); delivered to that
     *  one invited viewer. */
    STREAM_STAGE_INVITE("stream.stage.invite"),
    /** A guest's private stage grant — carries {@code stageMember} WITH their
     *  secret publish credentials (or {@code status=REMOVED} on revoke). Delivered
     *  to that one guest only. */
    STREAM_STAGE_GRANT("stream.stage.grant"),
    /** An ephemeral floating reaction. Carries {@code streamReaction}; delivered
     *  to everyone watching + the host. */
    STREAM_REACTION("stream.reaction"),
    /** A symbolic gift landed. Carries {@code streamGift}; delivered to everyone
     *  watching + the host. */
    STREAM_GIFT("stream.gift");

    private final String wire;

    ChatRealtimeEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
