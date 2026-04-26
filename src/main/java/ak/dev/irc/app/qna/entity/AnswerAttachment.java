package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.research.enums.MediaType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "answer_attachments",
        indexes = {
                @Index(name = "idx_aattach_answer", columnList = "answer_id"),
                @Index(name = "idx_aattach_type", columnList = "media_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerAttachment extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_aattach_answer"))
    private QuestionAnswer answer;

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

    /** MIME type e.g. video/mp4, image/png, application/pdf, application/zip */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 20)
    private MediaType mediaType;

    /** File size in bytes */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    // ── Display ───────────────────────────────────────────────────────────────

    /** Ordering within the answer's attachments */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** Caption / label for this attachment */
    @Column(name = "caption", length = 500)
    private String caption;

    // ── Video/Audio-specific ─────────────────────────────────────────────────

    /** Duration in seconds (only for video/audio) */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Thumbnail URL (for video/document previews) */
    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    /** S3/R2 object key for the thumbnail */
    @Column(name = "thumbnail_s3_key", columnDefinition = "TEXT")
    private String thumbnailS3Key;
}