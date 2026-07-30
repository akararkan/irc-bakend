package ak.dev.irc.app.chat.enums;

/**
 * Telegram-style ephemeral chat actions — what the composer is visibly doing,
 * broadcast as {@code activity} on the {@code typing} SSE event so other
 * members can render "@alice is recording a voice message…" instead of a bare
 * "typing…".
 *
 * <p>Ephemeral only: never stored beyond the short Redis TTL, auto-clears when
 * the client stops sending. Vocabulary matches the ika frontend's
 * {@code components/chat/activity.js}; {@link #parse} is deliberately tolerant
 * (Telegram-style {@code UPLOADING_*} synonyms map onto {@code SENDING_*},
 * anything unknown degrades to plain {@link #TYPING}) — an ephemeral cosmetic
 * hint must never fail a request over its adjective.</p>
 *
 * <p>Suggested mapping while composing each {@code MessageType}:
 * VOICE → {@link #RECORDING_VOICE} while the mic is live, then
 * {@link #SENDING_VOICE} during the upload; VIDEO_NOTE → the two
 * {@code *_VIDEO_NOTE} states; IMAGE/GIF → {@link #SENDING_PHOTO};
 * VIDEO → {@link #SENDING_VIDEO}; FILE → {@link #SENDING_FILE};
 * AUDIO → {@link #SENDING_AUDIO}; STICKER → {@link #CHOOSING_STICKER};
 * LOCATION → {@link #SENDING_LOCATION}.</p>
 */
public enum ChatAction {
    /** Composing a text message (the classic "typing…"). */
    TYPING,
    /** Holding the mic — recording an in-app voice note. */
    RECORDING_VOICE,
    /** Voice note recorded, upload in flight. */
    SENDING_VOICE,
    /** Recording a circular video message. */
    RECORDING_VIDEO_NOTE,
    /** Video note recorded, upload in flight. */
    SENDING_VIDEO_NOTE,
    /** Sending a photo / animated image. */
    SENDING_PHOTO,
    /** Sending a video. */
    SENDING_VIDEO,
    /** Sending a document / arbitrary file. */
    SENDING_FILE,
    /** Sending a music / audio file. */
    SENDING_AUDIO,
    /** Browsing the sticker picker. */
    CHOOSING_STICKER,
    /** Picking a location to share. */
    SENDING_LOCATION;

    /**
     * Lenient wire-value parser: case-insensitive, accepts Telegram-style
     * {@code UPLOADING_*} synonyms, and degrades unknown values (or null) to
     * {@link #TYPING} instead of erroring.
     */
    public static ChatAction parse(String raw) {
        if (raw == null || raw.isBlank()) return TYPING;
        String norm = raw.trim().toUpperCase();
        if (norm.startsWith("UPLOADING_")) norm = "SENDING_" + norm.substring("UPLOADING_".length());
        if (norm.equals("SENDING_DOCUMENT")) norm = "SENDING_FILE";
        try {
            return valueOf(norm);
        } catch (IllegalArgumentException e) {
            return TYPING;
        }
    }
}
