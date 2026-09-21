package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ffmpeg ladder transcoding for the media worker (replaces the single-output
 * {@code VideoProcessor}). One pass per rung: H.264 High@4.1 + AAC,
 * {@code +faststart}, lanczos short-edge scale that never upscales, fps capped
 * at {@code min(maxFps, source)}. The worker decides which rungs apply and
 * uploads each output file as it completes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTranscodeService {

    private final FfmpegRunner ffmpeg;
    private final MediaProperties props;

    /** ffprobe facts about a local video file. */
    public record SourceInfo(Integer width, Integer height, Integer durationMs) {
        public Integer shortEdge() {
            return width == null || height == null ? null : Math.min(width, height);
        }
    }

    /**
     * Probe dimensions and duration.
     *
     * @return the info, or {@code null} when ffprobe is unavailable or the
     *         file has no decodable video stream
     */
    public SourceInfo probe(Path video) {
        if (!ffmpeg.ffprobeAvailable()) return null;
        String out = ffmpeg.runForOutput(List.of(
                ffmpeg.ffprobeBin(), "-v", "quiet",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                video.toString()), 30);
        if (out == null || out.isBlank()) return null;
        Integer w = null, h = null, durationMs = null;
        // Lines: "width,height" (stream) and "duration" (format) — order per
        // section. Each line parses independently: duration-less containers
        // (MediaRecorder WebM, live-captured MKV) report "N/A" there, and that
        // must not discard perfectly good dimensions — a null-duration probe
        // still transcodes, a null probe fails the upload terminally.
        for (String line : out.split("\\R")) {
            String[] parts = line.trim().split(",");
            try {
                if (parts.length >= 2 && w == null) {
                    w = (int) Double.parseDouble(parts[0].trim());
                    h = (int) Double.parseDouble(parts[1].trim());
                } else if (parts.length == 1 && !parts[0].isBlank() && durationMs == null) {
                    durationMs = (int) (Double.parseDouble(parts[0].trim()) * 1000);
                }
            } catch (NumberFormatException ignored) {
                // "N/A" or junk on this line — keep whatever else parsed.
            }
        }
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        return new SourceInfo(w, h, durationMs);
    }

    /**
     * Transcode one rung to {@code out}.
     *
     * @param shortEdgeCap effective cap for this rung (already min'd with the
     *                     platform/tier caps by the caller)
     * @return true when the output file exists and is non-empty
     */
    public boolean transcodeRung(Path in, Path out, MediaProperties.Video.Rung rung, int shortEdgeCap) {
        // Downscale ONLY if larger; never upscale. -2 keeps dims even (H.264 needs it).
        String vf = String.format(
                "scale='if(gt(iw,ih), -2, min(%1$d,iw))':'if(gt(iw,ih), min(%1$d,ih), -2)':flags=lanczos,"
                        + "fps=fps='min(%2$d,source_fps)'",
                shortEdgeCap, props.getVideo().getMaxFps());

        // Forced IDR frames on a fixed grid keep every rung's keyframes at the
        // same instants, so HLS segmentation (a -c copy remux downstream) cuts
        // all variants at identical boundaries — the precondition for seamless
        // ABR switching. Scene-cut keyframes still land in between for quality.
        int kf = Math.max(1, props.getVideo().getHls().getKeyframeSeconds());

        List<String> cmd = List.of(
                ffmpeg.ffmpegBin(), "-y", "-nostdin", "-i", in.toString(),
                "-vf", vf,
                "-c:v", "libx264", "-preset", "medium", "-profile:v", "high", "-level", "4.1",
                "-crf", String.valueOf(rung.getCrf()),
                "-maxrate", rung.getMaxrate(), "-bufsize", rung.getBufsize(),
                "-force_key_frames", "expr:gte(t,n_forced*" + kf + ")",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", rung.getAudioBitrate(), "-ar", "48000", "-ac", "2",
                "-movflags", "+faststart",
                out.toString());

        FfmpegRunner.RunResult r = ffmpeg.run(cmd, props.getProcessing().getTimeoutSeconds());
        try {
            boolean ok = r.ok() && Files.exists(out) && Files.size(out) > 0;
            if (!ok) {
                log.warn("[MEDIA-VID] rung {} failed (exit={} timedOut={}): {}",
                        rung.getLabel(), r.exitCode(), r.timedOut(), tail(r.output()));
            }
            return ok;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(s.length() - 400);
    }
}
