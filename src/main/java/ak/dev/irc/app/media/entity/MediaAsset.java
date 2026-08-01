package ak.dev.irc.app.media.entity;

import ak.dev.irc.app.media.enums.MediaAssetType;
import ak.dev.irc.app.media.enums.MediaStatus;
import ak.dev.irc.app.media.enums.MediaTier;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A media asset and its processing state (spec §20.9). {@code storedBytes} is
 * deliberately denormalised so Section 15's storage report is a single indexed
 * {@code SUM}, never a bucket listing. {@code height}/{@code width} are always
 * within the caps for a READY asset (≤ 1080 short edge for video).
 */
@Entity
@Table(name = "media_assets",
       indexes = {
           @Index(name = "idx_media_owner", columnList = "owner_id, created_at"),
           @Index(name = "idx_media_hash", columnList = "content_hash"),
           @Index(name = "idx_media_status", columnList = "status")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private MediaAssetType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private MediaStatus status = MediaStatus.PENDING;

    /** SHA-256 of the original bytes — indexed for dedup (§20.5). */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "original_bytes")
    private Long originalBytes;

    /** Sum of all rendition bytes — powers §15 usage reporting. */
    @Column(name = "stored_bytes")
    @Builder.Default
    private Long storedBytes = 0L;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_tier", length = 16)
    @Builder.Default
    private MediaTier requestedTier = MediaTier.HIGH;

    @Column(name = "blurhash", length = 64)
    private String blurhash;

    /** Original MIME as declared / detected. */
    @Column(name = "mime", length = 100)
    private String mime;

    @Column(name = "error_message", length = 300)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When the original in {@code raw/} should be deleted (7 days, §20.6). */
    @Column(name = "purge_original_at")
    private LocalDateTime purgeOriginalAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
