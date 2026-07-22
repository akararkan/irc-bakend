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
    REQUEST_NEW("request.new");

    private final String wire;

    ChatRealtimeEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
