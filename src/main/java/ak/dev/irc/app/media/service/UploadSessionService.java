package ak.dev.irc.app.media.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.MediaMessages;
import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.media.dto.IngestResult;
import ak.dev.irc.app.media.entity.UploadSession;
import ak.dev.irc.app.media.enums.MediaSurface;
import ak.dev.irc.app.media.repository.UploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Chunked/resumable uploads for large files: a dropped connection resumes from
 * the last received chunk instead of starting over. The client inits a session
 * (fail-fast policy check), PUTs fixed-size chunks in any order (each retryable
 * and idempotent), and completes — at which point the chunks are assembled and
 * pushed through the exact same {@code MediaIngestService.ingest} path as a
 * normal multipart upload (magic-byte gate, caps, quota, image/video pipeline,
 * {@code media_assets} accounting). Nothing downstream can tell the difference.
 *
 * <p>Chunks spool to instance-local disk ({@code media.uploads.session-dir}),
 * so a session is pinned to the instance that opened it — fine for the current
 * single-instance deployment; sticky routing would be needed behind a
 * load balancer.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadSessionService {

    private final MediaProperties props;
    private final MediaIngestService mediaIngest;
    private final UploadSessionRepository sessionRepo;

    public record InitResult(UUID id, long chunkBytes, int totalChunks, Instant expiresAt) {}
    public record SessionStatus(UUID id, long chunkBytes, int totalChunks,
                                List<Integer> receivedChunks, Instant expiresAt) {}

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public InitResult init(UUID ownerId, String surfaceName, String fileName,
                           String mime, long totalBytes) {
        MediaSurface surface = parseSurface(surfaceName);
        if (fileName == null || fileName.isBlank() || totalBytes <= 0) {
            throw new BadRequestException(MediaMessages.MEDIA_INVALID_MSG, MediaMessages.MEDIA_INVALID);
        }
        mediaIngest.assertUploadAllowed(surface, fileName, mime, totalBytes);
        if (sessionRepo.countByOwnerId(ownerId) >= props.getUploads().getMaxOpenSessionsPerUser()) {
            throw new BadRequestException(MediaMessages.MEDIA_BUSY_MSG, MediaMessages.MEDIA_BUSY);
        }

        long chunkBytes = props.getUploads().getChunkBytes();
        int totalChunks = (int) ((totalBytes + chunkBytes - 1) / chunkBytes);
        Instant now = Instant.now();
        UploadSession session = UploadSession.builder()
                .id(UUID.randomUUID()).ownerId(ownerId).surface(surface.name())
                .fileName(sanitizeFileName(fileName)).mime(mime)
                .totalBytes(totalBytes).chunkBytes(chunkBytes).totalChunks(totalChunks)
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofHours(props.getUploads().getSessionTtlHours())))
                .build();
        try {
            Files.createDirectories(sessionDir(session.getId()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot create upload session dir", e);
        }
        sessionRepo.save(session);
        return new InitResult(session.getId(), chunkBytes, totalChunks, session.getExpiresAt());
    }

    /** Store one chunk (idempotent — re-PUT of the same index overwrites). */
    public void putChunk(UUID ownerId, UUID sessionId, int index, InputStream body) {
        UploadSession s = requireOpen(ownerId, sessionId);
        if (index < 0 || index >= s.getTotalChunks()) {
            throw new BadRequestException(
                    MediaMessages.UPLOAD_CHUNK_INVALID_MSG, MediaMessages.UPLOAD_CHUNK_INVALID);
        }
        long limit = s.getChunkBytes();
        Path dir = sessionDir(sessionId);
        try {
            Path tmp = Files.createTempFile(dir, "in-", ".tmp");
            try {
                long written = copyLimited(body, tmp, limit);
                long expected = expectedChunkSize(s, index);
                if (written != expected) {
                    throw new BadRequestException(
                            MediaMessages.UPLOAD_CHUNK_INVALID_MSG, MediaMessages.UPLOAD_CHUNK_INVALID);
                }
                Files.move(tmp, chunkPath(sessionId, index),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("chunk write failed", e);
        }
    }

    public SessionStatus status(UUID ownerId, UUID sessionId) {
        UploadSession s = requireOpen(ownerId, sessionId);
        return new SessionStatus(s.getId(), s.getChunkBytes(), s.getTotalChunks(),
                receivedChunks(sessionId), s.getExpiresAt());
    }

    /**
     * Assemble + ingest. On success — and on a policy rejection, which the same
     * bytes would only hit again — the session is cleaned up; a transient
     * failure (storage down) keeps it so the client can retry complete.
     */
    public IngestResult complete(UUID ownerId, UUID sessionId) {
        UploadSession s = requireOpen(ownerId, sessionId);
        List<Integer> received = receivedChunks(sessionId);
        if (received.size() != s.getTotalChunks()) {
            throw new BadRequestException(
                    MediaMessages.UPLOAD_SESSION_INCOMPLETE_MSG.formatted(received.size(), s.getTotalChunks()),
                    MediaMessages.UPLOAD_SESSION_INCOMPLETE);
        }

        Path assembled = sessionDir(sessionId).resolve("assembled.bin");
        try {
            try (OutputStream out = Files.newOutputStream(assembled)) {
                for (int i = 0; i < s.getTotalChunks(); i++) {
                    Files.copy(chunkPath(sessionId, i), out);
                }
            }
            if (Files.size(assembled) != s.getTotalBytes()) {
                cleanup(sessionId);
                sessionRepo.delete(s);
                throw new BadRequestException(
                        MediaMessages.UPLOAD_CHUNK_INVALID_MSG, MediaMessages.UPLOAD_CHUNK_INVALID);
            }
        } catch (IOException e) {
            throw new IllegalStateException("chunk assembly failed", e);
        }

        MediaSurface surface = parseSurface(s.getSurface());
        var file = new TempFileMultipartFile(assembled, s.getFileName(), s.getMime());
        IngestResult result;
        try {
            result = mediaIngest.ingest(file, surface, ownerId, "uploads/" + surface.configKey());
        } catch (BadRequestException e) {
            cleanup(sessionId);
            sessionRepo.delete(s);
            throw e;
        }
        cleanup(sessionId);
        sessionRepo.delete(s);
        return result;
    }

    public void cancel(UUID ownerId, UUID sessionId) {
        sessionRepo.findByIdAndOwnerId(sessionId, ownerId).ifPresent(s -> {
            cleanup(sessionId);
            sessionRepo.delete(s);
        });
    }

    // ── TTL sweep ────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 120_000L)
    public void sweepExpired() {
        for (UploadSession s : sessionRepo.findByExpiresAtBefore(Instant.now())) {
            cleanup(s.getId());
            sessionRepo.delete(s);
            log.info("[UPLOAD-SESSION] swept expired session {} ({})", s.getId(), s.getFileName());
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private UploadSession requireOpen(UUID ownerId, UUID sessionId) {
        UploadSession s = sessionRepo.findByIdAndOwnerId(sessionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("UploadSession", "id", sessionId));
        if (s.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(
                    MediaMessages.UPLOAD_SESSION_EXPIRED_MSG, MediaMessages.UPLOAD_SESSION_EXPIRED);
        }
        return s;
    }

    private static MediaSurface parseSurface(String name) {
        try {
            return MediaSurface.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new BadRequestException(
                    MediaMessages.MEDIA_TYPE_NOT_ALLOWED_MSG.formatted(name),
                    MediaMessages.MEDIA_TYPE_NOT_ALLOWED);
        }
    }

    private long expectedChunkSize(UploadSession s, int index) {
        if (index < s.getTotalChunks() - 1) return s.getChunkBytes();
        long tail = s.getTotalBytes() % s.getChunkBytes();
        return tail == 0 ? s.getChunkBytes() : tail;
    }

    private List<Integer> receivedChunks(UUID sessionId) {
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) return List.of();
        List<Integer> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".part")) {
                    try {
                        out.add(Integer.parseInt(n.substring(0, n.length() - 5)));
                    } catch (NumberFormatException ignored) { }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("cannot list upload session dir", e);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Copy up to {@code limit} bytes; anything beyond it makes the chunk invalid. */
    private static long copyLimited(InputStream in, Path target, long limit) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) {
                    throw new BadRequestException(
                            MediaMessages.UPLOAD_CHUNK_INVALID_MSG, MediaMessages.UPLOAD_CHUNK_INVALID);
                }
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private Path sessionDir(UUID sessionId) {
        return Path.of(props.getUploads().getSessionDir(), sessionId.toString());
    }

    private Path chunkPath(UUID sessionId, int index) {
        return sessionDir(sessionId).resolve(index + ".part");
    }

    private void cleanup(UUID sessionId) {
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
        try { Files.deleteIfExists(dir); } catch (IOException ignored) { }
    }

    private static String sanitizeFileName(String name) {
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        return base.isBlank() ? "file" : base;
    }
}
