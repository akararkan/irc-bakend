package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.dto.MediaVariants;
import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.repository.MediaAssetRepository;
import ak.dev.irc.app.media.repository.MediaRenditionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side bulk join from asset ids to client variant maps: exactly ONE
 * Postgres IN-query for renditions and ONE for asset status per page — the
 * same discipline as {@code PostHydrator}'s counter loads, never a per-row
 * point read. An empty id set costs zero queries.
 *
 * <p>Failure-soft: if Postgres hiccups, callers get an empty map and the DTOs
 * simply ship without variants (legacy URLs still render).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaVariantHydrator {

    private final MediaAssetRepository assetRepo;
    private final MediaRenditionRepository renditionRepo;
    private final MediaCdnRewriter cdnRewriter;

    /**
     * @param variants   client variant map ({@link MediaVariants} keys)
     * @param processing true while a video's ladder is still being produced
     * @param blurhash   compact placeholder hash, when one was computed
     */
    public record VariantSet(Map<String, String> variants, boolean processing,
                             Integer width, Integer height, Integer durationMs,
                             String blurhash) {

        /** Best playable PROGRESSIVE video URL for this set, or null. */
        public String bestVideoUrl() {
            return MediaVariants.bestVideoUrl(variants);
        }

        /** Adaptive (HLS master) URL for HLS-aware players, or null. */
        public String hlsUrl() {
            return MediaVariants.hlsUrl(variants);
        }
    }

    /** Bulk-load variant sets for a page of asset ids. Never throws. */
    public Map<UUID, VariantSet> load(Set<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return Map.of();
        try {
            Map<UUID, List<MediaRendition>> byAsset = new HashMap<>();
            for (MediaRendition r : renditionRepo.findByIdMediaIdIn(assetIds)) {
                byAsset.computeIfAbsent(r.getId().getMediaId(), k -> new ArrayList<>()).add(r);
            }
            Map<UUID, VariantSet> out = new HashMap<>();
            for (MediaAsset a : assetRepo.findAllById(assetIds)) {
                List<MediaRendition> renditions = byAsset.getOrDefault(a.getId(), List.of());
                out.put(a.getId(), new VariantSet(
                        rewriteAll(MediaVariants.toClientMap(renditions)),
                        a.getStatus() == MediaStatus.PROCESSING,
                        a.getWidth(), a.getHeight(), a.getDurationMs(),
                        a.getBlurhash()));
            }
            return out;
        } catch (Exception ex) {
            log.warn("[MEDIA-HYDRATE] variant load failed for {} asset(s): {}",
                    assetIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    /** CDN base applied at read time (no-op map reuse when unconfigured). */
    private Map<String, String> rewriteAll(Map<String, String> variants) {
        if (!cdnRewriter.active() || variants.isEmpty()) return variants;
        Map<String, String> out = new java.util.LinkedHashMap<>(variants.size());
        variants.forEach((k, v) -> out.put(k, cdnRewriter.rewrite(v)));
        return out;
    }

    /** Parse helper for the string ids Cassandra rows carry ("" → null). */
    public static UUID parseAssetId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Collect the non-null asset ids from a batch of raw string ids. */
    public static Set<UUID> collect(Collection<String> rawIds) {
        Set<UUID> out = new java.util.HashSet<>();
        if (rawIds == null) return out;
        for (String raw : rawIds) {
            UUID id = parseAssetId(raw);
            if (id != null) out.add(id);
        }
        return out;
    }
}
