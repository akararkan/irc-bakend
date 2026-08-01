package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Video transcoder (spec §20.4). When {@code media.processing.enabled} and an
 * ffmpeg binary are present, downscales the short edge to the 1080 cap (never
 * upscaling) and re-encodes H.264 High + AAC with {@code +faststart}. Otherwise
 * it degrades to a <b>passthrough</b> — the original is stored as the sole
 * rendition and the pipeline still completes (status READY).
 *
 * <p>The scale filter is where the 1080 cap is enforced:
 * {@code scale=... short edge → min(1080, edge)}. This mirrors the existing
 * live-streaming ffmpeg invocation pattern in the chat module.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessor {

    private final MediaProperties props;

    public record Result(byte[] bytes, Integer width, Integer height, String mime,
                         String label, boolean transcoded) {}

    public Result process(byte[] input, int maxShortEdge) {
        int cap = Math.min(maxShortEdge, props.getVideo().getMaxShortEdge());
        if (!props.getProcessing().isEnabled() || !ffmpegAvailable()) {
            log.info("[MEDIA-VID] transcode disabled/unavailable — passthrough ({} bytes)", input.length);
            return new Result(input, null, null, "video/mp4", "original", false);
        }
        Path in = null, out = null;
        try {
            in = Files.createTempFile("media-in-", ".bin");
            out = Files.createTempFile("media-out-", ".mp4");
            Files.write(in, input);

            // Downscale ONLY if larger; never upscale. -2 keeps dims even (H.264 needs it).
            String vf = String.format(
                    "scale='if(gt(iw,ih), -2, min(%1$d,iw))':'if(gt(iw,ih), min(%1$d,ih), -2)':flags=lanczos,"
                            + "fps=fps='min(%2$d,source_fps)'",
                    cap, props.getVideo().getMaxFps());

            List<String> cmd = List.of(
                    props.getProcessing().getFfmpegBin(), "-y", "-i", in.toString(),
                    "-vf", vf,
                    "-c:v", "libx264", "-preset", "medium", "-profile:v", "high", "-level", "4.1",
                    "-crf", String.valueOf(props.getVideo().getCrf()),
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", props.getVideo().getAudioBitrate(), "-ar", "48000", "-ac", "2",
                    "-movflags", "+faststart",
                    out.toString());

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(props.getProcessing().getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IllegalStateException("ffmpeg timed out");
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                throw new IllegalStateException("ffmpeg exit " + p.exitValue());
            }
            byte[] result = Files.readAllBytes(out);
            log.info("[MEDIA-VID] transcoded {} → {} bytes (cap short-edge {})", input.length, result.length, cap);
            return new Result(result, null, null, "video/mp4", "1080p", true);
        } catch (Exception ex) {
            log.warn("[MEDIA-VID] transcode failed ({}), falling back to passthrough", ex.getMessage());
            return new Result(input, null, null, "video/mp4", "original", false);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder(props.getProcessing().getFfmpegBin(), "-version")
                    .redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }
}
