package ak.dev.irc.app.post.sound.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.enums.SoundStatus;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "sounds",
    indexes = {
        @Index(name = "idx_sound_category",  columnList = "category"),
        @Index(name = "idx_sound_status",    columnList = "status"),
        @Index(name = "idx_sound_uploader",  columnList = "uploader_id"),
        @Index(name = "idx_sound_use_count", columnList = "use_count")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sound extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Identity ──────────────────────────────────────────────────────────────

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "artist_name", length = 150)
    private String artistName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ── Category & status ─────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private SoundCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SoundStatus status = SoundStatus.PENDING_REVIEW;

    // ── Media ─────────────────────────────────────────────────────────────────

    @Column(name = "audio_url", nullable = false, columnDefinition = "TEXT")
    private String audioUrl;

    @Column(name = "audio_s3_key", columnDefinition = "TEXT")
    private String audioS3Key;

    @Column(name = "cover_art_url", columnDefinition = "TEXT")
    private String coverArtUrl;

    @Column(name = "cover_art_s3_key", columnDefinition = "TEXT")
    private String coverArtS3Key;

    /** Duration in seconds — validated ≤ 600s on upload. */
    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    /** Default offset for the 30s story/reel preview clip. */
    @Column(name = "default_clip_start", nullable = false)
    @Builder.Default
    private int defaultClipStart = 0;

    @Column(name = "mime_type", length = 50)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /**
     * JSON array of ~100 amplitude samples (0.0–1.0) for waveform rendering.
     * Generated server-side via ffmpeg on upload.
     */
    @Column(name = "waveform_data", columnDefinition = "TEXT")
    private String waveformData;

    // ── Attribution & licensing ───────────────────────────────────────────────

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "license", length = 80)
    private String license;

    /** Null for platform-seeded sounds. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_id",
                foreignKey = @ForeignKey(name = "fk_sound_uploader"))
    private User uploader;

    // ── Counters ──────────────────────────────────────────────────────────────

    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private long useCount = 0L;

    @Version
    private Long version;
}
