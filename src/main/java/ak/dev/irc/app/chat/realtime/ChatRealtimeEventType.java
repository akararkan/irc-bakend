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
    STREAM_VIEWER("stream.viewer"),
    STREAM_CHAT("stream.chat");

    private final String wire;

    ChatRealtimeEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
