package ak.dev.irc.app.media.enums;

/** Kind of media asset (spec §20.9). */
public enum MediaAssetType {
    IMAGE,
    VIDEO,
    AUDIO,
    /** Long-form video (feed / FILM) — up to 10 min, 512 MB. */
    FILM,
    /** Short clip — up to 90 s, 200 MB. */
    VIDEO_CLIP;

    public boolean isVideo() {
        return this == VIDEO || this == FILM || this == VIDEO_CLIP;
    }

    public static MediaAssetType parse(String raw, MediaAssetType fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
