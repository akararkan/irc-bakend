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

    /** A long broadcast can take a while to re-encode; bound it so a wedged
     *  ffmpeg cannot hold the end-of-stream path open forever. */
    private static final long COMBINE_TIMEOUT_MINUTES = 30L;

    private final Path root;
    private final String ffmpegBin;
    private final String ffprobeBin;

    public RecordingStorageService(
            @Value("${app.streaming.recordings-dir:./recordings}") String recordingsDir,
            @Value("${app.streaming.ffmpeg-bin:ffmpeg}") String ffmpegBin,
            @Value("${app.streaming.ffprobe-bin:ffprobe}") String ffprobeBin) {
        this.root = Path.of(recordingsDir).toAbsolutePath().normalize();
        this.ffmpegBin = ffmpegBin;
        this.ffprobeBin = ffprobeBin;
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

    /**
     * Join every recorded part into ONE playable file, and replace the parts with
     * it. Called once, when the stream ends.
     *
     * <p>A broadcast produces several parts for two reasons: the host toggles
     * recording on and off, and MediaMTX restarts its recorder whenever the
     * published track set changes. Either way "download my recording" should mean
     * one file, not a manifest the caller has to walk.</p>
     *
     * <p><b>Why this re-encodes instead of stream-copying.</b> Measured on real
     * WebRTC recordings from this stack: each part's first ~1.2s of video is
     * undecodable (MediaMTX writes frames before the H264 SPS/PPS reach the
     * container — its own log says {@code SPS not received yet}), and each part's
     * video tail is truncated ~2.8s short of its audio. A {@code -c copy} concat
     * carries both defects into every seam, so a recording toggled on and off
     * four times would freeze the picture four times while the sound played on —
     * exactly the failure this feature exists to remove. Decoding and re-encoding
     * drops the undecodable lead-in and rebuilds a clean, continuously-keyframed
     * timeline. It costs CPU once per broadcast, off the request path.</p>
     *
     * <p>Failure is non-destructive: if ffmpeg is missing, errors, or times out,
     * the original parts are left exactly as they were and the caller falls back
     * to the existing multi-part manifest. A recording that is awkward to
     * download beats a recording that was destroyed while being tidied.</p>
     *
     * @return the combined file, or empty if nothing was combined (0/1 parts, or
     *         the join failed — both mean "carry on with what is on disk").
     */
    public Optional<Path> combineParts(UUID streamId) {
        List<Path> parts = listParts(streamId);
        if (parts.size() < 2) return Optional.empty();      // nothing to join

        Path dir = streamDir(streamId);
        Path listFile = dir.resolve(".concat.txt");
        Path output = dir.resolve(".combined.mp4");
        List<Path> normalized = new ArrayList<>();
        try {
            for (Path p : parts) {
                String name = p.getFileName().toString();
                if (!SAFE_PART.matcher(name).matches()) {
                    log.warn("[REC] combine skipped for {}: unexpected part name {}", streamId, name);
                    return Optional.empty();
                }
            }

            /* PASS 1 — normalize each part on its own.
               `-shortest` is the load-bearing flag, and it was learned the hard
               way: measured, every part's audio outlives its video by ~3s (the
               final fMP4 fragment is not flushed on teardown). Concatenating raw
               parts therefore leaves a video-shaped hole exactly where two takes
               meet, and the joined file freezes for three seconds at every seam —
               the very bug this method exists to prevent. Re-encoding alone does
               not help: re-encoding a hole yields a hole. `-shortest` ends each
               part when its SHORTEST stream ends, cutting the audio-only tail so
               the seams butt cleanly.
               The re-encode also drops each part's undecodable lead-in (MediaMTX
               writes video before the H264 parameter sets reach the container),
               and `-avoid_negative_ts make_zero` re-bases each part to zero so
               the concatenated timeline is monotonic. */
            for (int i = 0; i < parts.size(); i++) {
                Path norm = dir.resolve(".norm-" + i + ".mp4");
                /* Seek both streams to where the video actually becomes decodable.
                   Measured: a part's audio starts at 0 but its first decodable
                   video frame is 0.45–1.5s in, so trimming only the tail still
                   left a sub-second frozen seam. `-ss` before `-i` drops that
                   lead-in from BOTH streams, which keeps A/V in sync (shifting
                   video alone would desync it by the same amount) and lands the
                   joined file with no gap at all. */
                List<String> cmd = new ArrayList<>(List.of(
                        ffmpegBin, "-hide_banner", "-loglevel", "error", "-y"));
                firstVideoFrameSeconds(parts.get(i))
                        .ifPresent(ss -> { cmd.add("-ss"); cmd.add(ss); });
                cmd.addAll(List.of(
                        "-i", parts.get(i).toString(),
                        "-map", "0:v:0", "-map", "0:a:0?",
                        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-shortest", "-avoid_negative_ts", "make_zero",
                        norm.toString()));
                if (!run(dir, cmd, streamId, "normalize part " + i)) return Optional.empty();
                if (!Files.isRegularFile(norm) || Files.size(norm) == 0L) return Optional.empty();
                normalized.add(norm);
            }

            /* PASS 2 — join. Every part now carries identical codec parameters, so
               this is a stream copy: no second generation of quality loss, and it
               costs seconds rather than minutes. */
            StringBuilder sb = new StringBuilder();
            for (Path n : normalized) sb.append("file '").append(n.getFileName()).append("'\n");
            Files.writeString(listFile, sb.toString());

            if (!run(dir, List.of(
                    ffmpegBin, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-c", "copy", "-movflags", "+faststart",
                    output.toString()), streamId, "concat")) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0L) return Optional.empty();

            /* Only now, with a verified output in hand, are the parts expendable.
               The combined file is renamed into place LAST so a crash mid-tidy
               can never leave the stream dir empty. */
            Path finalName = dir.resolve("recording.mp4");
            for (Path p : parts) Files.deleteIfExists(p);
            Files.move(output, finalName, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[REC] combined {} parts into one file for {}", parts.size(), streamId);
            return Optional.of(finalName);
        } catch (IOException e) {
            log.warn("[REC] combine failed for {}: {}", streamId, e.getMessage());
            return Optional.empty();
        } finally {
            for (Path n : normalized) { try { Files.deleteIfExists(n); } catch (IOException ignored) { /* scratch */ } }
            try { Files.deleteIfExists(listFile); } catch (IOException ignored) { /* scratch file */ }
            try { Files.deleteIfExists(output); }   catch (IOException ignored) { /* moved on success */ }
        }
    }

    /**
     * Timestamp of the first DECODABLE video frame in a part, as an ffmpeg-ready
     * seconds string. Empty when ffprobe is unavailable or says nothing useful —
     * the join then proceeds without the seek, which is the merely-good outcome
     * rather than no outcome.
     *
     * <p>{@code -show_frames} is deliberate: it decodes, so it skips the frames
     * MediaMTX wrote before the H264 parameter sets landed. {@code -show_packets}
     * would report those undecodable frames as present and hand back a timestamp
     * that still has a hole after it.</p>
     */
    private Optional<String> firstVideoFrameSeconds(Path part) {
        String out = runForOutput(List.of(
                ffprobeBin, "-v", "quiet", "-select_streams", "v",
                "-show_frames", "-show_entries", "frame=pts_time",
                "-of", "csv=p=0", "-read_intervals", "%+10",
                part.toString()));
        if (out == null) return Optional.empty();
        for (String line : out.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                double v = Double.parseDouble(t);
                // 0 means there is nothing to trim; anything negative is nonsense.
                return v > 0.001 ? Optional.of(t) : Optional.empty();
            } catch (NumberFormatException e) { return Optional.empty(); }
        }
        return Optional.empty();
    }

    /** Run a probe and return stdout, or null on any failure. */
    private String runForOutput(List<String> cmd) {
        try {
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String out = new String(proc.getInputStream().readAllBytes());
            if (!proc.waitFor(1, java.util.concurrent.TimeUnit.MINUTES)) { proc.destroyForcibly(); return null; }
            return proc.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Run one ffmpeg step. False on any failure — the caller then abandons the
     *  join and leaves the original parts untouched. */
    private boolean run(Path dir, List<String> cmd, UUID streamId, String step) {
        try {
            Process proc = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes());
            if (!proc.waitFor(COMBINE_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                log.warn("[REC] {} timed out for {}", step, streamId);
                return false;
            }
            if (proc.exitValue() != 0) {
                log.warn("[REC] {} failed for {} (exit {}): {}", step, streamId, proc.exitValue(), out.strip());
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("[REC] {} failed for {}: {}", step, streamId, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
