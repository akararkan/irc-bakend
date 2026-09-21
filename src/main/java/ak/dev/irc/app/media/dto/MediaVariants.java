package ak.dev.irc.app.media.dto;

import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.RenditionLabels;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place rendition labels become client-facing variant keys. Every DTO
 * that exposes a {@code variants} map builds it here, so web and mobile see a
 * single stable contract:
 *
 * <pre>{@code
 * thumb / thumbSmall            — 320 list thumb, 150 square micro thumb
 * feed / feedWebp               — display class (1080/1280/512 by surface)
 * full / fullWebp               — zoom class (1440)
 * poster                        — video poster frame
 * preview                       — short animated WebP hover preview
 * v1080 / v720 / v480 / v360    — H.264 ladder (progressive MP4)
 * hls                           — CMAF/fMP4 HLS master playlist (ABR)
 * original                      — stored original (video/audio/file)
 * }</pre>
 */
public final class MediaVariants {

    private MediaVariants() {}

    private static final Map<String, String> LABEL_TO_CLIENT_KEY = Map.ofEntries(
            Map.entry(RenditionLabels.THUMB_320, "thumb"),
            Map.entry(RenditionLabels.THUMB_SQ150, "thumbSmall"),
            Map.entry(RenditionLabels.JPEG_1080, "feed"),
            Map.entry(RenditionLabels.WEBP_1080, "feedWebp"),
            Map.entry(RenditionLabels.JPEG_1280, "feed"),
            Map.entry(RenditionLabels.WEBP_1280, "feedWebp"),
            Map.entry(RenditionLabels.AVATAR_512, "feed"),
            Map.entry(RenditionLabels.JPEG_1440, "full"),
            Map.entry(RenditionLabels.WEBP_1440, "fullWebp"),
            Map.entry(RenditionLabels.JPEG, "feed"),          // legacy single-JPEG assets
            Map.entry(RenditionLabels.POSTER, "poster"),
            Map.entry(RenditionLabels.PREVIEW, "preview"),
            Map.entry(RenditionLabels.HLS, "hls"),
            Map.entry(RenditionLabels.P1080, "v1080"),
            Map.entry(RenditionLabels.P720, "v720"),
            Map.entry(RenditionLabels.P480, "v480"),
            Map.entry(RenditionLabels.P360, "v360"),
            Map.entry(RenditionLabels.ORIGINAL, "original"));

    /** Client variant map from rendition rows; unknown labels are skipped. */
    public static Map<String, String> toClientMap(Collection<MediaRendition> renditions) {
        Map<String, String> out = new LinkedHashMap<>();
        if (renditions == null) return out;
        for (MediaRendition r : renditions) {
            String key = LABEL_TO_CLIENT_KEY.get(r.getId().getLabel());
            if (key != null && r.getUrl() != null) {
                out.putIfAbsent(key, r.getUrl());
            }
        }
        return out;
    }

    /** Best display URL for lists/feeds: feed → full → original → anything. */
    public static String primaryUrl(Map<String, String> variants) {
        if (variants == null || variants.isEmpty()) return null;
        if (variants.containsKey("feed")) return variants.get("feed");
        if (variants.containsKey("full")) return variants.get("full");
        if (variants.containsKey("original")) return variants.get("original");
        return variants.values().iterator().next();
    }

    /** Best thumbnail URL: thumb → thumbSmall → poster → null. */
    public static String thumbnailUrl(Map<String, String> variants) {
        if (variants == null) return null;
        if (variants.containsKey("thumb")) return variants.get("thumb");
        if (variants.containsKey("thumbSmall")) return variants.get("thumbSmall");
        return variants.get("poster");
    }

    /**
     * Best playable PROGRESSIVE video URL once processed: v720 → v1080 → v480
     * → v360 → original. Deliberately excludes {@code hls} — every existing
     * client can play an MP4, only HLS-aware players ask for {@link #hlsUrl}.
     */
    public static String bestVideoUrl(Map<String, String> variants) {
        if (variants == null) return null;
        for (String key : new String[]{"v720", "v1080", "v480", "v360", "original"}) {
            String url = variants.get(key);
            if (url != null) return url;
        }
        return null;
    }

    /** Adaptive (HLS master) URL when the packager has produced one, else null. */
    public static String hlsUrl(Map<String, String> variants) {
        return variants == null ? null : variants.get("hls");
    }
}
