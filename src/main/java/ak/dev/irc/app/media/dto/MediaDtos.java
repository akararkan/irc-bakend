package ak.dev.irc.app.media.dto;

import ak.dev.irc.app.media.entity.MediaAsset;
import ak.dev.irc.app.media.entity.MediaRendition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** Media pipeline request/response DTOs (spec §20.4). */
public final class MediaDtos {

    private MediaDtos() {}

    public record UploadIntentRequest(
            @NotBlank String mime,
            @Positive long sizeBytes,
            String sha256,
            @NotBlank String type) {}

    /** Response to an upload-intent. On a dedup hit {@code presignedPutUrl} is
     *  null and {@code status} is READY — the client can use the asset at once. */
    public record UploadIntentResponse(
            java.util.UUID mediaId,
            String presignedPutUrl,
            boolean deduped,
            String status) {}

    public record RenditionDto(String label, String url, Long bytes,
                               Integer width, Integer height, String mime) {
        public static RenditionDto of(MediaRendition r) {
            return new RenditionDto(r.getId().getLabel(), r.getUrl(), r.getBytes(),
                    r.getWidth(), r.getHeight(), r.getMime());
        }
    }

    public record MediaStatusResponse(
            java.util.UUID mediaId,
            String type,
            String status,
            Integer width,
            Integer height,
            Integer durationMs,
            String blurhash,
            Long storedBytes,
            String errorMessage,
            List<RenditionDto> renditions) {
        public static MediaStatusResponse of(MediaAsset a, List<MediaRendition> rends) {
            return new MediaStatusResponse(
                    a.getId(), a.getType().name(), a.getStatus().name(),
                    a.getWidth(), a.getHeight(), a.getDurationMs(), a.getBlurhash(),
                    a.getStoredBytes(), a.getErrorMessage(),
                    rends.stream().map(RenditionDto::of).toList());
        }
    }
}
