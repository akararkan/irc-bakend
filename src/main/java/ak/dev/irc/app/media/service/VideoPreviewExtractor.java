package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Short animated WebP preview (hover/scrub teaser) from the first seconds of a
 * video — the moving equivalent of the poster frame. Same contract as
 * {@link VideoPosterExtractor}: <b>failure is always soft</b>; requires the
 * ffmpeg build to ship libwebp (the {@link FfmpegRunner#webpAvailable()} probe
 * the image pipeline already uses).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoPreviewExtractor {

    private final FfmpegRunner ffmpeg;
    private final MediaProperties props;

    /**
     * @return animated WebP bytes, or {@code null} when extraction wasn't
     *         possible (disabled, no libwebp, unreadable input)
     */
    public byte[] extract(Path video) {
        MediaProperties.Video.Preview cfg = props.getVideo().getPreview();
        if (!cfg.isEnabled() || !ffmpeg.webpAvailable()) return null;
        Path out = null;
        try {
            out = Files.createTempFile("media-preview-", ".webp");
            List<String> cmd = List.of(
                    ffmpeg.ffmpegBin(), "-y", "-nostdin",
                    "-ss", "0", "-t", String.valueOf(cfg.getSeconds()),
                    "-i", video.toString(),
                    "-vf", "fps=%d,scale='min(%d,iw)':-2:flags=lanczos"
                            .formatted(cfg.getFps(), cfg.getEdge()),
                    "-an",
                    "-c:v", "libwebp", "-q:v", String.valueOf(cfg.getQuality()),
                    "-loop", "0",
                    out.toString());
            FfmpegRunner.RunResult r = ffmpeg.run(cmd, cfg.getTimeoutSeconds());
            if (!r.ok() || !Files.exists(out) || Files.size(out) == 0) return null;
            byte[] webp = Files.readAllBytes(out);
            log.info("[MEDIA-PREVIEW] animated preview extracted ({} bytes)", webp.length);
            return webp;
        } catch (Exception ex) {
            log.warn("[MEDIA-PREVIEW] preview extraction failed: {}", ex.getMessage());
            return null;
        } finally {
            if (out != null) {
                try { Files.deleteIfExists(out); } catch (Exception ignored) { }
            }
        }
    }
}
