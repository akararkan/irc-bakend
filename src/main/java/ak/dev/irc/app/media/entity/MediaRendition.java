package ak.dev.irc.app.media.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

/**
 * One produced rendition of a {@link MediaAsset} (spec §20.9). Composite key
 * {@code (mediaId, label)}. Object keys embed the content hash
 * ({@code media/{hash}/1080p.mp4}) so responses are immutable-cacheable.
 *
 * <p>Labels: {@code 1080p | 720p | 480p | 360p | avif | webp | jpeg | poster |
 * captions | hls | original}.</p>
 */
@Entity
@Table(name = "media_renditions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaRendition {

    @EmbeddedId
    private MediaRenditionId id;

    @Column(name = "object_key", columnDefinition = "text")
    private String objectKey;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "mime", length = 64)
    private String mime;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @EqualsAndHashCode
    public static class MediaRenditionId implements Serializable {
        @Column(name = "media_id", nullable = false)
        private UUID mediaId;
        @Column(name = "label", nullable = false, length = 16)
        private String label;
    }
}
