package ak.dev.irc.app.chat.enums;

/**
 * The kind of a message row. {@code SYSTEM} messages are group events
 * (member added, title changed, …) stored inline in the timeline so they
 * paginate normally and need no separate mechanism.
 */
public enum MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE,
    FILE,
    SYSTEM
}
