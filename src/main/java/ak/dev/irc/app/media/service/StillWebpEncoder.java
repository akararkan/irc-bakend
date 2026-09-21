package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Still-image WebP encoding via ffmpeg's libwebp — the JDK has no WebP writer
 * and the repo deliberately takes no imaging dependency, so WebP variants are
 * a best-effort bonus on top of the guaranteed JPEG output.
 *
 * <p><b>Never throws.</b> A missing binary, a build without libwebp, a timeout
 * or a bad encode all return {@code null}; the caller simply ships JPEG-only.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StillWebpEncoder {

    private final FfmpegRunner ffmpeg;
    private final MediaProperties props;

    /** Whether WebP output is possible at all (config + binary + libwebp). */
    public boolean available() {
        return props.getImage().isWebpEnabled() && ffmpeg.webpAvailable();
    }

    /**
     * Encode JPEG or PNG bytes to WebP.
     *
     * @return WebP bytes, or {@code null} when encoding wasn't possible
     */
    public byte[] encode(byte[] jpegOrPng) {
        if (!available() || jpegOrPng == null || jpegOrPng.length == 0) return null;
        Path in = null, out = null;
        try {
            in = Files.createTempFile("webp-in-", ".img");
            out = Files.createTempFile("webp-out-", ".webp");
            Files.write(in, jpegOrPng);
            List<String> cmd = List.of(
                    ffmpeg.ffmpegBin(), "-y", "-nostdin",
                    "-i", in.toString(),
                    "-frames:v", "1",
                    "-c:v", "libwebp",
                    "-quality", String.valueOf(props.getImage().getWebpQuality()),
                    "-preset", "picture",
                    "-an",
                    out.toString());
            FfmpegRunner.RunResult r = ffmpeg.run(cmd, props.getImage().getWebpTimeoutSeconds());
            if (!r.ok() || !Files.exists(out) || Files.size(out) == 0) {
                log.debug("[MEDIA-WEBP] encode failed (exit={}, timedOut={})", r.exitCode(), r.timedOut());
                return null;
            }
            return Files.readAllBytes(out);
        } catch (Exception ex) {
            log.debug("[MEDIA-WEBP] encode failed: {}", ex.getMessage());
            return null;
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }
}
