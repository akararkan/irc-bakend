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
    /** Short animated WebP hover/scrub preview. */
    public static final String PREVIEW = "preview";
    public static final String CAPTIONS = "captions";
    /** CMAF/fMP4 HLS master playlist ({@code media/{assetId}/hls/master.m3u8}). */
    public static final String HLS     = "hls";
    /** The single stored copy when the passthrough processor keeps the source. */
    public static final String ORIGINAL = "original";

    // ── Image ladder (multi-variant pipeline) ────────────────────────────────
    public static final String JPEG_1440 = "jpeg_1440";   // zoom / full-screen
    public static final String WEBP_1440 = "webp_1440";
    public static final String JPEG_1080 = "jpeg_1080";   // feed / display
    public static final String WEBP_1080 = "webp_1080";
    public static final String JPEG_1280 = "jpeg_1280";   // chat display class
    public static final String WEBP_1280 = "webp_1280";
    public static final String AVATAR_512 = "avatar_512"; // square profile crop
    public static final String THUMB_320 = "thumb_320";   // list thumbnail (JPEG)
    public static final String THUMB_SQ150 = "thumb_sq150"; // square micro thumb (JPEG)
}
