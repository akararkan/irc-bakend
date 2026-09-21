package ak.dev.irc.app.media.dto;

import ak.dev.irc.app.media.enums.MediaKind;

import java.util.Map;
import java.util.UUID;

/**
 * What a surface gets back from {@code MediaIngestService.ingest}. Call sites
 * never branch on the pipeline being on or off — in kill-switch (legacy) mode
 * {@code assetId} is null, {@code url} is the stored original, and
 * {@code variants} is empty.
 *
 * @param assetId      media_assets row id; null in legacy mode
 * @param kind         coarse classification of what was stored
 * @param url          primary URL: image → feed-class variant; video → the
 *                     immediately-playable original; audio/file → the original
 * @param thumbnailUrl 320 thumb (image) / poster (video) / null
 * @param storageKey   object key backing {@code url}
 * @param variants     client variant map ({@link MediaVariants}); grows for
 *                     video when the ladder lands
 * @param processing   true while the video ladder is still being produced
 */
public record IngestResult(
        UUID assetId,
        MediaKind kind,
        String url,
        String thumbnailUrl,
        String storageKey,
        Map<String, String> variants,
        Integer width,
        Integer height,
        Integer durationSeconds,
        Long bytes,
        String mime,
        String fileName,
        boolean processing) {}
