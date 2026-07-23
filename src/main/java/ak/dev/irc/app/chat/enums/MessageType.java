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
    /** In-app recorded voice note (waveform, ms duration). */
    VOICE,
    /** Music / audio file (artist/title live in the file's own metadata). */
    AUDIO,
    /** Animated media (animated image or short soundless video loop). */
    GIF,
    STICKER,
    FILE,
    /** Poll/quiz — the poll payload rides in the message's {@code poll} column. */
    POLL,
    /** Geo point / live location — payload in the {@code location} column. */
    LOCATION,
    /** Shared contact card — payload in the {@code contact} column. */
    CONTACT,
    /** Telegram-style circular video message (media kind {@code VIDEO_NOTE}). */
    VIDEO_NOTE,
    SYSTEM
}
