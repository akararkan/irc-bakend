package ak.dev.irc.app.chat.enums;

/**
 * A conversation is either a 1:1 {@code DIRECT} thread or a many-member
 * {@code GROUP}. Everything about the message log, delivery and requests is
 * identical between the two — only membership, roles and system messages differ.
 */
public enum ConversationType {
    DIRECT,
    GROUP,
    /** Telegram-style broadcast channel: admins post, everyone else subscribes and reads. */
    CHANNEL
}
