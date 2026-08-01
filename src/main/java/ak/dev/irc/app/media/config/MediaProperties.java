package ak.dev.irc.app.media.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code media.*} configuration block (spec §20.2). All caps live here,
 * not in code, so they can be tuned without a release. Defaults match the spec.
 *
 * <p><b>The hard rule:</b> {@code video.maxShortEdge = 1080} — do not raise it.
 * Any source above 1080 px on its short edge is downscaled to exactly 1080.</p>
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "media")
public class MediaProperties {

    private final Image image = new Image();
    private final Video video = new Video();
    private final Limits limits = new Limits();
    private final Processing processing = new Processing();

    @Getter @Setter
    public static class Image {
        private int maxLongEdge = 1920;        // HD / Full-HD class cap
        private int chatMaxLongEdge = 1280;
        private int profileEdge = 512;
        private int avifQuality = 55;
        private int webpQuality = 80;
        private int jpegQuality = 82;          // progressive JPEG fallback (pure-JDK output)
    }

    @Getter @Setter
    public static class Video {
        private int maxShortEdge = 1080;       // HARD CAP — do not raise
        private int maxFps = 30;
        private int crf = 23;
        private String audioBitrate = "128k";
    }

    @Getter @Setter
    public static class Limits {
        private long imageMaxBytes = 26_214_400L;    // 25 MB
        private long videoMaxBytes = 536_870_912L;   // 512 MB
        private long maxInputMegapixels = 100L;      // decompression-bomb guard
    }

    @Getter @Setter
    public static class Processing {
        /** ffmpeg / ffprobe binaries; reuses the streaming defaults when unset. */
        private String ffmpegBin = "ffmpeg";
        private String ffprobeBin = "ffprobe";
        /** When false (default), video uses the passthrough transcoder (no ffmpeg). */
        private boolean enabled = false;
        /** Wall-clock timeout for a single ffmpeg run. */
        private int timeoutSeconds = 600;
    }
}
