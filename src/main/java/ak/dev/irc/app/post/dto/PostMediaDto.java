package ak.dev.irc.app.post.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Rich per-item media descriptor on a post — the size-appropriate variant map
 * clients should render from ({@code thumb} in lists, {@code feed} in the
 * feed, {@code full} on zoom, {@code v720}/{@code v1080} for video). The
 * legacy flat {@code mediaUrls}/{@code mediaTypes} lists stay untouched for
 * older clients.
 *
 * @param url        best display URL for this item (video upgrades to the
 *                   ladder once processed)
 * @param variants   client variant map; empty for legacy (pre-pipeline) media
 * @param processing true while a video's rendition ladder is still being made
 * @param blurhash   compact placeholder hash (paint-before-bytes), null for
 *                   legacy media — additive, mapper-safe
 */
public record PostMediaDto(
        String url,
        String type,
        String thumbnailUrl,
        UUID assetId,
        Map<String, String> variants,
        Boolean processing,
        Integer width,
        Integer height,
        Integer durationSeconds,
        String blurhash) {}
