package ak.dev.irc.app.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A chunked/resumable upload in flight. The row carries ownership + policy
 * facts; the received chunks themselves live on local disk under
 * {@code media.uploads.session-dir}/{id}/ — the directory listing is the
 * source of truth for which chunks have arrived (no bitmap to keep in sync).
 * A row's existence means the session is open: complete/cancel delete it, the
 * TTL sweeper reaps the rest. Surface is stored as plain text (not an enum
 * column) so widening {@code MediaSurface} never trips a CHECK constraint.
 */
@Entity
@Table(name = "upload_sessions", indexes = {
        @Index(name = "idx_upload_sessions_owner", columnList = "owner_id"),
        @Index(name = "idx_upload_sessions_expires", columnList = "expires_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadSession {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** {@link ak.dev.irc.app.media.enums.MediaSurface} name. */
    @Column(nullable = false, length = 40)
    private String surface;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(length = 255)
    private String mime;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "chunk_bytes", nullable = false)
    private long chunkBytes;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
