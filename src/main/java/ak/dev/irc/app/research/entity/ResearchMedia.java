package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.research.enums.MediaType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "research_media",
    indexes = {
        @Index(name = "idx_rmedia_research", columnList = "research_id"),
        @Index(name = "idx_rmedia_type",     columnList = "media_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchMedia extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_rmedia_research"))
    private Research research;

    // ── File metadata ─────────────────────────────────────────────────────────

    /** Public CDN / pre-signed URL */
    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    /** S3/R2 object key for management (delete, re-sign) */
    @Column(name = "s3_key", nullable = false, columnDefinition = "TEXT")
    private String s3Key;

    /** Original file name as uploaded */
    @Column(name = "original_file_name", nullable = false, length = 500)
    private String originalFileName;

    /** MIME type e.g. video/mp4, image/png, application/pdf */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 20)
    private MediaType mediaType;

    /** File size in bytes */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    // ── Display ───────────────────────────────────────────────────────────────

    /** Ordering within the research media gallery */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** Caption / label for this media item */
    @Column(name = "caption", length = 500)
    private String caption;

    /** Alt text for accessibility (images) */
    @Column(name = "alt_text", length = 300)
    private String altText;

    // ── Video-specific ────────────────────────────────────────────────────────

    /** Duration in seconds (only for video/audio) */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Thumbnail URL (auto-generated or uploaded) */
    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    // ── Image-specific ────────────────────────────────────────────────────────

    @Column(name = "width_px")
    private Integer widthPx;

    @Column(name = "height_px")
    private Integer heightPx;
}
