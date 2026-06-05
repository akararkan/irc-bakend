package ak.dev.irc.app.research.service;

import lombok.extern.slf4j.Slf4j;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts metadata (duration) from uploaded video files.
 * Supports MP4, QuickTime (.mov), and other ISO base media file formats
 * via mp4parser. WebM and other non-ISO formats return {@code null} — that
 * is expected, not an error.
 *
 * <p><b>Robustness contract.</b> This extractor is best-effort and
 * <em>never</em> throws. If anything goes wrong (null/consumed stream,
 * unsupported container, missing movie box, IO error, library bug) it
 * logs at DEBUG and returns {@code null}. The caller stores null on the
 * row and the front-end {@code <video>} element reads the real duration
 * at playback time. The duration field is a UI nicety, not a
 * load-bearing data point — never let extraction failure block a
 * successful upload.</p>
 */
@Slf4j
@Component
public class VideoMetadataExtractor {

    /**
     * Extracts video duration in seconds from an uploaded video file.
     *
     * <p>Reads the multipart via {@link MultipartFile#getInputStream()},
     * with a {@link MultipartFile#getBytes()} fallback for the edge case
     * where the underlying servlet implementation returns a {@code null}
     * stream because the body part was touched earlier in the filter
     * chain. Spools to a temp file because mp4parser needs seekable
     * access; the temp file is always deleted in {@code finally}.</p>
     *
     * @param video the uploaded video file
     * @return duration in seconds, or {@code null} if extraction fails
     */
    public Integer extractDurationSeconds(MultipartFile video) {
        if (video == null || video.isEmpty()) return null;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("video-meta-", ".tmp");

            if (!copyMultipartToTempFile(video, tempFile)) {
                log.debug("VideoMetadataExtractor: empty/null payload for '{}' — duration unavailable",
                        video.getOriginalFilename());
                return null;
            }

            // mp4parser only reads ISO base media format files. WebM / MKV
            // / OGG return null here — that's expected behavior.
            try (IsoFile isoFile = new IsoFile(tempFile.toString())) {
                MovieHeaderBox mvhd = isoFile.getMovieBox().getMovieHeaderBox();
                if (mvhd.getTimescale() == 0) return null;
                double durationInSeconds = (double) mvhd.getDuration() / mvhd.getTimescale();
                int rounded = (int) Math.round(durationInSeconds);
                log.debug("Extracted video duration: {}s (raw: {}s)", rounded, durationInSeconds);
                return rounded > 0 ? rounded : null;
            }
        } catch (Exception e) {
            // Demoted to DEBUG — this is the normal path for unsupported
            // containers (webm) and is not worth a WARN-level log line.
            log.debug("VideoMetadataExtractor: could not extract duration from '{}' ({}): {}",
                    video.getOriginalFilename(), video.getContentType(), e.getMessage());
            return null;
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Writes the multipart to {@code target}. Tries {@code getInputStream()}
     * first; if that returns null or fails, falls back to {@code getBytes()}.
     * Returns {@code true} when at least one byte was written. Never throws.
     */
    private static boolean copyMultipartToTempFile(MultipartFile video, Path target) {
        // Attempt 1 — stream copy (memory-efficient).
        try (InputStream in = video.getInputStream()) {
            if (in != null) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return Files.size(target) > 0L;
            }
        } catch (Exception streamEx) {
            log.debug("VideoMetadataExtractor: getInputStream() path failed for '{}': {} — trying getBytes()",
                    video.getOriginalFilename(), streamEx.getMessage());
        }
        // Attempt 2 — byte[] copy. Buffers the whole file into memory but
        // works when the underlying ServletPart can't serve a stream twice.
        try {
            byte[] bytes = video.getBytes();
            if (bytes == null || bytes.length == 0) return false;
            Files.write(target, bytes);
            return true;
        } catch (Exception bytesEx) {
            log.debug("VideoMetadataExtractor: getBytes() fallback failed for '{}': {}",
                    video.getOriginalFilename(), bytesEx.getMessage());
            return false;
        }
    }
}
