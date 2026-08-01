package ak.dev.irc.app.media.enums;

/**
 * Canonical rendition labels (spec §20.4/§20.9). Stored as plain strings on
 * {@code media_renditions.label} — several ("1080p") begin with a digit and so
 * cannot be enum constants; a small constants holder keeps them typo-safe.
 */
public final class RenditionLabels {

    private RenditionLabels() {}

    public static final String P1080   = "1080p";
    public static final String P720    = "720p";
    public static final String P480    = "480p";
    public static final String P360    = "360p";
    public static final String AVIF    = "avif";
    public static final String WEBP    = "webp";
    public static final String JPEG    = "jpeg";
    public static final String POSTER  = "poster";
    public static final String CAPTIONS = "captions";
    public static final String HLS     = "hls";
    /** The single stored copy when the passthrough processor keeps the source. */
    public static final String ORIGINAL = "original";
}
