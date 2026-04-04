package ak.dev.irc.app.post.entity;


import ak.dev.irc.app.post.enums.PostMediaType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "post_media", indexes = {
        @Index(name = "idx_media_post", columnList = "post_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private PostMediaType mediaType;

    @Column(name = "url", nullable = false)
    private String url;

    /** Thumbnail URL (video / reel) */
    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    /** Alt text for accessibility */
    @Column(name = "alt_text")
    private String altText;

    /** Duration in seconds for VIDEO / VOICE_NOTE / AUDIO_TRACK */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** File size in bytes */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** MIME type e.g. audio/webm, video/mp4 */
    @Column(name = "mime_type")
    private String mimeType;

    // ── voice-note specific ───────────────────────────────────
    /** JSON array of peak amplitudes */
    @Column(name = "waveform_data", columnDefinition = "TEXT")
    private String waveformData;

    /** Auto-generated transcript */
    @Column(name = "transcript", columnDefinition = "TEXT")
    private String transcript;

    /** Sort order within the post */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}