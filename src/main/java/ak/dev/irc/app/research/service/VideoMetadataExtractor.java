package ak.dev.irc.app.research.service;

import lombok.extern.slf4j.Slf4j;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts metadata (duration) from uploaded video files.
 * Supports MP4, QuickTime (.mov), and other ISO base media file formats.
 */
@Slf4j
@Component
public class VideoMetadataExtractor {

    /**
     * Extracts video duration in seconds from an uploaded video file.
     *
     * @param video the uploaded video file
     * @return duration in seconds, or {@code null} if extraction fails
     */
    public Integer extractDurationSeconds(MultipartFile video) {
        if (video == null || video.isEmpty()) return null;

        Path tempFile = null;
        try {
            // Write to temp file — IsoFile needs seekable access
            tempFile = Files.createTempFile("video-meta-", ".tmp");
            try (InputStream in = video.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            try (IsoFile isoFile = new IsoFile(tempFile.toString())) {
                MovieHeaderBox mvhd = isoFile.getMovieBox().getMovieHeaderBox();
                double durationInSeconds = (double) mvhd.getDuration() / mvhd.getTimescale();
                int rounded = (int) Math.round(durationInSeconds);
                log.debug("Extracted video duration: {}s (raw: {}s)", rounded, durationInSeconds);
                return rounded;
            }
        } catch (Exception e) {
            log.warn("Could not extract video duration (format may be unsupported): {}",
                    e.getMessage());
            return null;
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }
}