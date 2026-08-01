package ak.dev.irc.app.media.enums;

/**
 * User-selected media upload quality tier (spec §20.3).
 *
 * <p>The tier is a <em>hint that saves the user's bandwidth</em> — the client
 * may pre-compress to it, and the backend never produces renditions above it.
 * It can only request <em>equal or lower</em> quality than the platform cap;
 * it can never raise it. In every tier the backend still re-encodes: "High"
 * means <em>up to the cap</em>, never <em>unmodified original</em>.</p>
 */
public enum MediaTier {

    /** ~70% smaller uploads — image long-edge 1080px, video short-edge 480p. */
    DATA_SAVER(1080, 480),

    /** ~40% smaller uploads — image long-edge 1440px, video short-edge 720p. */
    STANDARD(1440, 720),

    /** Full permitted quality — image long-edge 1920px (cap), video 1080p (cap). */
    HIGH(1920, 1080);

    private final int imageLongEdge;
    private final int videoShortEdge;

    MediaTier(int imageLongEdge, int videoShortEdge) {
        this.imageLongEdge  = imageLongEdge;
        this.videoShortEdge = videoShortEdge;
    }

    /** Requested image long-edge for this tier (px). */
    public int imageLongEdge()  { return imageLongEdge; }

    /** Requested video short-edge for this tier (px). */
    public int videoShortEdge() { return videoShortEdge; }

    /** Lenient parse — unknown/blank resolves to {@link #HIGH} (the default). */
    public static MediaTier parse(String raw) {
        if (raw == null || raw.isBlank()) return HIGH;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return HIGH;
        }
    }
}
