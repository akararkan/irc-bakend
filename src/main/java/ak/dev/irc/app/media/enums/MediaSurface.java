package ak.dev.irc.app.media.enums;

import java.util.Locale;
import java.util.Set;

import static ak.dev.irc.app.media.enums.MediaKind.AUDIO;
import static ak.dev.irc.app.media.enums.MediaKind.FILE;
import static ak.dev.irc.app.media.enums.MediaKind.IMAGE;
import static ak.dev.irc.app.media.enums.MediaKind.VIDEO;

/**
 * Per-surface media policy: what kinds are accepted, which image plan is
 * produced, how many files per request, and the video duration cap. These are
 * the code defaults — {@code media.surfaces.<config-key>.*} overrides them at
 * runtime (see {@code MediaProperties.SurfaceOverride}).
 */
public enum MediaSurface {

    //                 allowed kinds                    plan               max  video-cap  quota
    //                                                                     count (seconds) exempt
    POST_MEDIA        (Set.of(IMAGE, VIDEO, AUDIO, FILE), PlanKind.FULL,   10,  600,       false),
    STORY             (Set.of(IMAGE, VIDEO),            PlanKind.FEED,      1,   60,       false),
    CHAT_MEDIA        (Set.of(IMAGE, VIDEO, AUDIO, FILE), PlanKind.CHAT,   10,  180,       false),
    AVATAR            (Set.of(IMAGE),                   PlanKind.PROFILE,   1, null,       false),
    PROFILE_COVER     (Set.of(IMAGE),                   PlanKind.FEED,      1, null,       false),
    CHANNEL_PHOTO     (Set.of(IMAGE),                   PlanKind.PROFILE,   1, null,       false),
    CHANNEL_COVER     (Set.of(IMAGE),                   PlanKind.FEED,      1, null,       false),
    RESEARCH_MEDIA    (Set.of(IMAGE, VIDEO, AUDIO, FILE), PlanKind.FULL,   20,  600,       false),
    RESEARCH_PROMO    (Set.of(VIDEO),                   PlanKind.NONE,      1,  300,       false),
    RESEARCH_PROMO_THUMB(Set.of(IMAGE),                 PlanKind.FEED,      1, null,       false),
    RESEARCH_COVER    (Set.of(IMAGE),                   PlanKind.FEED,      1, null,       false),
    RESEARCH_COMMENT  (Set.of(IMAGE, VIDEO),            PlanKind.FEED,      1,  180,       false),
    QNA_ANSWER_MEDIA  (Set.of(IMAGE, VIDEO),            PlanKind.FEED,      1,  300,       false),
    QNA_ATTACHMENT    (Set.of(IMAGE, VIDEO, FILE),      PlanKind.FULL,      1,  300,       false),
    VOICE             (Set.of(AUDIO),                   PlanKind.NONE,      1, null,       false),
    DOCUMENT          (Set.of(FILE),                    PlanKind.NONE,      1, null,       false),
    SOUND_AUDIO       (Set.of(AUDIO, VIDEO),            PlanKind.NONE,      1, null,       true),
    SOUND_COVER       (Set.of(IMAGE),                   PlanKind.FEED,      1, null,       true);

    /** Which image rendition set this surface produces. */
    public enum PlanKind { FULL, FEED, CHAT, PROFILE, NONE }

    /** Profile-class surfaces get a tighter image byte cap than the global 25 MB. */
    private static final long PROFILE_IMAGE_MAX_BYTES = 10_485_760L; // 10 MB

    private final Set<MediaKind> allowedKinds;
    private final PlanKind planKind;
    private final int maxCount;
    private final Integer maxDurationSeconds;
    private final boolean quotaExempt;

    MediaSurface(Set<MediaKind> allowedKinds, PlanKind planKind, int maxCount,
                 Integer maxDurationSeconds, boolean quotaExempt) {
        this.allowedKinds = allowedKinds;
        this.planKind = planKind;
        this.maxCount = maxCount;
        this.maxDurationSeconds = maxDurationSeconds;
        this.quotaExempt = quotaExempt;
    }

    public Set<MediaKind> allowedKinds() { return allowedKinds; }
    public PlanKind planKind()           { return planKind; }
    public int maxCount()                { return maxCount; }
    public Integer maxDurationSeconds()  { return maxDurationSeconds; }
    public boolean quotaExempt()         { return quotaExempt; }

    /** Key under {@code media.surfaces.*} for runtime overrides. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Surfaces that store everything verbatim regardless of classified kind.
     * SOUND_AUDIO must accept video/mp4 (how browsers declare m4a) without
     * entering the video transcode ladder.
     */
    public boolean passthroughOnly() {
        return this == VOICE || this == DOCUMENT || this == SOUND_AUDIO;
    }

    /** Tighter image byte cap for square-crop profile surfaces; null → global limit. */
    public Long imageMaxBytesDefault() {
        return (this == AVATAR || this == CHANNEL_PHOTO || this == PROFILE_COVER
                || this == CHANNEL_COVER) ? PROFILE_IMAGE_MAX_BYTES : null;
    }

    /** The {@code media_assets.type} to record for an accepted kind. */
    public MediaAssetType assetTypeFor(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> MediaAssetType.IMAGE;
            case AUDIO -> MediaAssetType.AUDIO;
            case FILE -> MediaAssetType.DOCUMENT;
            case VIDEO -> this == STORY ? MediaAssetType.VIDEO_CLIP
                    : this == SOUND_AUDIO ? MediaAssetType.AUDIO   // m4a declared video/mp4
                    : MediaAssetType.VIDEO;
        };
    }
}
