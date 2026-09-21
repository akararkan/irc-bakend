package ak.dev.irc.app.media.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Extracts a single poster frame (JPEG) from an uploaded video so list
 * surfaces have an image to paint while the player attaches. Runs through
 * {@link FfmpegRunner} but is deliberately <b>not</b> gated on
 * {@code media.processing.enabled} — that flag controls the transcode
 * pipeline; a poster is cheap enough to attempt whenever the binary exists.
 *
 * <p><b>Failure is always soft.</b> Missing ffmpeg, a timeout, or a corrupt
 * file logs a warning and returns {@code null} — the upload itself must never
 * fail because a thumbnail couldn't be made.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoPosterExtractor {

    /** Seek target for the frame — past any leading black/fade-in frame. */
    private static final String POSTER_SEEK_SECONDS = "0.4";
    /** Poster width cap; height follows the aspect ratio (-2 keeps it even). */
    private static final int POSTER_MAX_WIDTH = 720;
    /** MJPEG qscale 4 ≈ JPEG quality ~80. */
    private static final String POSTER_JPEG_QSCALE = "4";
    /** One frame from a local file — seconds, not the 10-minute transcode budget. */
    private static final int TIMEOUT_SECONDS = 30;

    private final FfmpegRunner ffmpeg;

    /**
     * Extract one poster frame from {@code video}.
     *
     * @return JPEG bytes, or {@code null} when extraction wasn't possible
     *         (no ffmpeg, timeout, unreadable/too-short input).
     */
    public byte[] extract(MultipartFile video) {
        if (!ffmpeg.ffmpegAvailable()) {
            log.warn("[MEDIA-POSTER] ffmpeg unavailable — video posts ship without a thumbnail");
            return null;
        }
        Path in = null;
        try {
            in = Files.createTempFile("poster-in-", ".bin");
            try (InputStream s = video.getInputStream()) {
                Files.copy(s, in, StandardCopyOption.REPLACE_EXISTING);
            }
            return extract(in);
        } catch (Exception ex) {
            log.warn("[MEDIA-POSTER] poster extraction failed ({}) — thumbnail left null", ex.getMessage());
            return null;
        } finally {
            deleteQuietly(in);
        }
    }

    /**
     * Same extraction from a video already on local disk (the worker path);
     * the caller keeps ownership of the file.
     */
    public byte[] extract(Path video) {
        if (!ffmpeg.ffmpegAvailable()) {
            log.warn("[MEDIA-POSTER] ffmpeg unavailable — video ships without a thumbnail");
            return null;
        }
        // Second pass at t=0 covers clips shorter than the seek target.
        byte[] jpeg = grabFrame(video, POSTER_SEEK_SECONDS);
        if (jpeg == null) jpeg = grabFrame(video, "0");
        return jpeg;
    }

    private byte[] grabFrame(Path in, String seek) {
        Path out = null;
        try {
            out = Files.createTempFile("poster-out-", ".jpg");
            // -ss before -i = fast input seek. Scale caps width at 720 without
            // ever upscaling; -2 keeps the height even for the JPEG encoder.
            List<String> cmd = List.of(
                    ffmpeg.ffmpegBin(), "-y", "-nostdin",
                    "-ss", seek, "-i", in.toString(),
                    "-frames:v", "1",
                    "-vf", "scale='min(%d,iw)':-2".formatted(POSTER_MAX_WIDTH),
                    "-q:v", POSTER_JPEG_QSCALE,
                    out.toString());
            FfmpegRunner.RunResult r = ffmpeg.run(cmd, TIMEOUT_SECONDS);
            if (!r.ok() || !Files.exists(out) || Files.size(out) == 0) {
                return null;   // no frame at this seek — caller may retry at t=0
            }
            byte[] jpeg = Files.readAllBytes(out);
            log.info("[MEDIA-POSTER] extracted poster frame at t={}s ({} bytes)", seek, jpeg.length);
            return jpeg;
        } catch (Exception ex) {
            log.warn("[MEDIA-POSTER] frame grab at t={}s failed: {}", seek, ex.getMessage());
            return null;
        } finally {
            deleteQuietly(out);
        }
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }
}
