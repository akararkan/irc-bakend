package ak.dev.irc.app.settings.core.entity;

import ak.dev.irc.app.media.enums.MediaTier;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Media preference block (spec §20.3) — <b>[C → B]</b>.
 *
 * <p>The client picks a preferred {@link #uploadQuality} tier; the backend reads
 * it (echoed on the upload-intent as {@code X-Media-Tier}) so it never produces
 * renditions above the requested tier — but the platform cap always wins. The
 * auto-download and playback fields are pure client policy.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaSettings {

    /** DATA_SAVER | STANDARD | HIGH — the ceiling the backend honours for this user. */
    @Builder.Default
    private MediaTier uploadQuality = MediaTier.HIGH;

    /** DATA_SAVER_ONLY | SAME_AS_WIFI — client-enforced cellular guard. */
    @Builder.Default
    private String uploadOverCellular = "SAME_AS_WIFI";

    /** NEVER | WIFI | WIFI_AND_CELLULAR — client only. */
    @Builder.Default
    private String autoDownloadPhotos = "WIFI";

    /** NEVER | WIFI | WIFI_AND_CELLULAR — client only. */
    @Builder.Default
    private String autoDownloadVideos = "WIFI";

    /** AUTO | P360 | P480 | P720 | P1080 — selects a rendition from the manifest. */
    @Builder.Default
    private String playbackQuality = "AUTO";

    public static MediaSettings defaults() {
        return MediaSettings.builder().build();
    }
}
