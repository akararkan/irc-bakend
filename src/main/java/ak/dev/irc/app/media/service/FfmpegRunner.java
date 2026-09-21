package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Central ffmpeg/ffprobe process helper for the media pipeline. Owns the
 * cached availability probes (so uploads never spawn {@code ffmpeg -version}
 * per request) and the run-with-hard-timeout pattern proven in
 * {@code RecordingStorageService}: output is drained on a separate thread so a
 * chatty transcode can never deadlock on a full pipe buffer, and a timeout
 * {@code destroyForcibly()}s the process.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegRunner {

    /** Output capture cap — enough for ffprobe CSV / error tails, never a transcript. */
    private static final int MAX_CAPTURED_OUTPUT = 64 * 1024;

    private final MediaProperties props;

    private volatile Boolean ffmpegPresent;
    private volatile Boolean ffprobePresent;
    private volatile Boolean webpPresent;

    public record RunResult(int exitCode, String output, boolean timedOut) {
        public boolean ok() { return !timedOut && exitCode == 0; }
    }

    public String ffmpegBin()  { return props.getProcessing().getFfmpegBin(); }
    public String ffprobeBin() { return props.getProcessing().getFfprobeBin(); }

    public boolean ffmpegAvailable() {
        Boolean cached = ffmpegPresent;
        if (cached != null) return cached;
        boolean ok = probeBinary(ffmpegBin(), "-version");
        ffmpegPresent = ok;
        if (!ok) log.warn("[MEDIA-FFMPEG] '{}' not runnable — ffmpeg-dependent steps degrade", ffmpegBin());
        return ok;
    }

    public boolean ffprobeAvailable() {
        Boolean cached = ffprobePresent;
        if (cached != null) return cached;
        boolean ok = probeBinary(ffprobeBin(), "-version");
        ffprobePresent = ok;
        if (!ok) log.warn("[MEDIA-FFMPEG] '{}' not runnable — media probing degrades", ffprobeBin());
        return ok;
    }

    /** Whether the ffmpeg build ships the libwebp still-image encoder. */
    public boolean webpAvailable() {
        Boolean cached = webpPresent;
        if (cached != null) return cached;
        boolean ok = false;
        if (ffmpegAvailable()) {
            RunResult r = run(List.of(ffmpegBin(), "-hide_banner", "-encoders"), 10);
            ok = r.ok() && r.output().contains("libwebp");
        }
        webpPresent = ok;
        if (!ok) log.info("[MEDIA-FFMPEG] libwebp encoder unavailable — image output is JPEG-only");
        return ok;
    }

    /**
     * Run a command with a hard wall-clock timeout. stderr is merged into stdout
     * and drained concurrently (capped capture) so the child can never block on
     * a full pipe. Never throws for process-level failure — inspect the result.
     */
    public RunResult run(List<String> cmd, int timeoutSeconds) {
        return run(cmd, timeoutSeconds, null);
    }

    /**
     * Same, with an explicit working directory. The HLS packager needs it:
     * ffmpeg writes segment paths into playlists exactly as given, so segment
     * filenames must be RELATIVE (resolved against {@code workDir}) or the
     * manifest would embed absolute local temp paths.
     */
    public RunResult run(List<String> cmd, int timeoutSeconds, java.nio.file.Path workDir) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (workDir != null) pb.directory(workDir.toFile());
            p = pb.start();
            StringBuilder captured = new StringBuilder();
            Thread drainer = drain(p.getInputStream(), captured);
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                drainer.join(2000);
                log.warn("[MEDIA-FFMPEG] timed out after {}s: {}", timeoutSeconds, cmd.get(0));
                return new RunResult(-1, captured.toString(), true);
            }
            drainer.join(5000);
            return new RunResult(p.exitValue(), captured.toString(), false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new RunResult(-1, "", true);
        } catch (Exception ex) {
            log.warn("[MEDIA-FFMPEG] failed to run {}: {}", cmd.isEmpty() ? "?" : cmd.get(0), ex.getMessage());
            return new RunResult(-1, ex.getMessage() == null ? "" : ex.getMessage(), false);
        }
    }

    /** Run and return trimmed output, or {@code null} unless the run succeeded. */
    public String runForOutput(List<String> cmd, int timeoutSeconds) {
        RunResult r = run(cmd, timeoutSeconds);
        return r.ok() ? r.output().trim() : null;
    }

    private Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (sink.length() < MAX_CAPTURED_OUTPUT) {
                        sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
                // stream closes when the process dies — nothing to do
            }
        }, "ffmpeg-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private boolean probeBinary(String bin, String arg) {
        try {
            Process p = new ProcessBuilder(bin, arg).redirectErrorStream(true).start();
            // Version banners are tiny — safe to wait without a drainer.
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
