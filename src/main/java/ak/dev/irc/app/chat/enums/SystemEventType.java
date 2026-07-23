package ak.dev.irc.app.chat.enums;

/**
 * The concrete event carried by a {@link MessageType#SYSTEM} message. The client
 * renders these centred and non-interactive (e.g. "Aram added Sara").
 */
public enum SystemEventType {
    GROUP_CREATED,
    MEMBER_ADDED,
    MEMBER_LEFT,
    MEMBER_REMOVED,
    ROLE_CHANGED,
    TITLE_CHANGED,
    DESCRIPTION_CHANGED,
    AVATAR_CHANGED,
    DISAPPEARING_CHANGED,
    PINNED,
    OWNERSHIP_TRANSFERRED
}
