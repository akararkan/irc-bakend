package ak.dev.irc.app.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * On-disk store for live-stream recordings. MediaMTX (see {@code mediamtx.yml}
 * {@code recordPath}) writes fMP4 parts to {@code <recordings-dir>/<stream-id>/}
 * on a bind-mounted volume, so the app — running on the host — reads and serves
 * them straight from disk with no media round trip.
 *
 * <p><b>Layout:</b> one directory per stream id (a UUID, so the segment is
 * traversal-safe by construction); inside it, one or more {@code *.mp4} parts.</p>
 *
 * <p><b>Safety:</b> a download names a part with an opaque filename; every path
 * is re-resolved under the stream dir and rejected if it escapes it, and the
 * filename must match {@link #SAFE_PART} — belt and suspenders against
 * traversal. The stream-id segment itself is never attacker-controlled (the
 * controller binds it as a {@link UUID}).</p>
 *
 * <p><b>Complexity:</b> {@link #listParts}/{@link #totalBytes} are O(parts) (a
 * single directory scan; parts per stream is tiny — one per segment window);
 * {@link #resolvePart} is O(1); {@link #deleteRecording} is O(parts).</p>
 */
@Slf4j
@Service
public class RecordingStorageService {

    /** Recorded parts are fMP4 files; nothing else in a stream dir is downloadable. */
    private static final String PART_GLOB = "*.mp4";
    /** Whitelisted part filename: no separators, no dot-dot, ends in .mp4. */
    private static final Pattern SAFE_PART = Pattern.compile("^[A-Za-z0-9._-]+\\.mp4$");

    private final Path root;

    public RecordingStorageService(
            @Value("${app.streaming.recordings-dir:./recordings}") String recordingsDir) {
        this.root = Path.of(recordingsDir).toAbsolutePath().normalize();
    }

    /** Absolute, normalized directory that holds this stream's parts. */
    private Path streamDir(UUID streamId) {
        return root.resolve(streamId.toString()).normalize();
    }

    /** True when the stream has at least one recorded part on disk. Stops at the
     *  first match — O(1) in the common case. */
    public boolean hasRecording(UUID streamId) {
        Path dir = streamDir(streamId);
        if (!Files.isDirectory(dir)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, PART_GLOB)) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            log.debug("[REC] hasRecording scan failed for {}: {}", streamId, e.getMessage());
            return false;
        }
    }

    /** All recorded parts for a stream, oldest-first (filenames are timestamped,
     *  so lexicographic order is chronological). Empty when nothing was recorded. */
    public List<Path> listParts(UUID streamId) {
        Path dir = streamDir(streamId);
        if (!Files.isDirectory(dir)) return List.of();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, PART_GLOB)) {
            List<Path> parts = new ArrayList<>();
            for (Path p : ds) if (Files.isRegularFile(p)) parts.add(p);
            parts.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return parts;
        } catch (IOException e) {
            log.warn("[REC] listParts failed for {}: {}", streamId, e.getMessage());
            return List.of();
        }
    }

    /** Total bytes across every part — one scan. */
    public long totalBytes(UUID streamId) {
        long total = 0L;
        for (Path p : listParts(streamId)) total += sizeOf(p);
        return total;
    }

    /** Byte size of a part (0 if unreadable). */
    public long sizeOf(Path part) {
        try { return Files.size(part); } catch (IOException e) { return 0L; }
    }

    /** Last-modified instant of a part (epoch if unreadable). */
    public java.time.Instant modifiedAt(Path part) {
        try { return Files.getLastModifiedTime(part).toInstant(); }
        catch (IOException e) { return java.time.Instant.EPOCH; }
    }

    /**
     * Resolve a caller-supplied part filename to a real file inside this stream's
     * dir, or {@link Optional#empty()} if it is malformed, escapes the dir, or is
     * missing. The one gate every download must pass through.
     */
    public Optional<Path> resolvePart(UUID streamId, String file) {
        if (file == null || !SAFE_PART.matcher(file).matches()) return Optional.empty();
        Path dir = streamDir(streamId);
        Path candidate = dir.resolve(file).normalize();
        if (!candidate.startsWith(dir)) return Optional.empty();          // escaped the dir
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /** Delete every recorded part and the stream dir itself. Idempotent (a
     *  missing dir is a no-op). Returns the number of files removed. */
    public int deleteRecording(UUID streamId) {
        Path dir = streamDir(streamId);
        if (!Files.isDirectory(dir)) return 0;
        int removed = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            // deepest-first so files are gone before their directory
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                boolean wasFile = Files.isRegularFile(p);
                if (Files.deleteIfExists(p) && wasFile) removed++;
            }
        } catch (IOException e) {
            log.warn("[REC] deleteRecording failed for {}: {}", streamId, e.getMessage());
        }
        return removed;
    }
}
