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
    private final Ingest ingest = new Ingest();
    private final Security security = new Security();
    private final Uploads uploads = new Uploads();
    private final Serving serving = new Serving();
    /** Per-surface cap overrides keyed by {@link ak.dev.irc.app.media.enums.MediaSurface} name (lower-kebab). */
    private final java.util.Map<String, SurfaceOverride> surfaces = new java.util.HashMap<>();

    @Getter @Setter
    public static class Image {
        private int maxLongEdge = 1920;        // HD / Full-HD class cap
        private int chatMaxLongEdge = 1280;
        private int profileEdge = 512;
        private int avifQuality = 55;
        private int webpQuality = 80;
        private int jpegQuality = 82;          // progressive JPEG fallback (pure-JDK output)
        private int thumbEdge = 320;           // list/feed thumbnail long edge
        private int squareThumbEdge = 150;     // square-crop micro thumb (avatars, dense lists)
        /** Still-image WebP via ffmpeg (libwebp); auto-off when the binary is absent. */
        private boolean webpEnabled = true;
        private int webpTimeoutSeconds = 10;   // per-encode hard kill
        /** Inline latency budget for a sync image ingest; WebP rungs are skipped past it. */
        private long syncBudgetMs = 8000;
        /** Concurrent in-request decodes (heap guard — a 100 MP decode is ~400 MB). */
        private int maxConcurrent = 3;
    }

    @Getter @Setter
    public static class Video {
        private int maxShortEdge = 1080;       // HARD CAP — do not raise
        private int maxFps = 30;
        private int crf = 23;
        private String audioBitrate = "128k";
        /** Rendition ladder, ascending. Empty → code defaults (360/480/720/1080). */
        private java.util.List<Rung> ladder = new java.util.ArrayList<>();
        private final DurationCaps durationCaps = new DurationCaps();
        private final Hls hls = new Hls();
        private final Preview preview = new Preview();
        /**
         * Days after READY before a video's stored {@code original} rendition is
         * purged (object + row) — only when an HLS master exists, so playback
         * never loses its last source. {@code -1} (default) disables the purge.
         */
        private int purgeLadderOriginalAfterDays = -1;

        /**
         * HLS/CMAF packaging of the H.264 ladder (spec: adaptive delivery).
         * Packaging is a remux ({@code -c copy}) of already-encoded rungs into
         * fMP4 segments + playlists — cheap relative to the encode itself.
         * Active only when {@code media.processing.enabled} is also true.
         */
        @Getter @Setter
        public static class Hls {
            private boolean enabled = true;
            /** Target segment duration (snaps to the forced keyframe grid). */
            private int segmentSeconds = 4;
            /** Forced IDR interval — every rung keyframes at the same instants
             *  so ABR switches are seamless. Divides segmentSeconds evenly. */
            private int keyframeSeconds = 2;
            /** Variant listed first in the master playlist — the rung players
             *  start on before bandwidth estimation kicks in. */
            private String startLabel = "480p";
        }

        /** Short animated WebP hover/scrub preview (soft-fail, like the poster). */
        @Getter @Setter
        public static class Preview {
            private boolean enabled = true;
            private double seconds = 2.5;
            private int fps = 12;
            /** Width cap; height follows aspect (-2 keeps it even). */
            private int edge = 240;
            /** libwebp quality (0-100 scale as -q:v). */
            private int quality = 45;
            private int timeoutSeconds = 60;
        }

        /** One H.264 ladder rung. */
        @Getter @Setter
        public static class Rung {
            private String label;
            private int shortEdge;
            private int crf;
            private String maxrate;
            private String bufsize;
            private String audioBitrate;

            public Rung() {}
            public Rung(String label, int shortEdge, int crf,
                        String maxrate, String bufsize, String audioBitrate) {
                this.label = label; this.shortEdge = shortEdge; this.crf = crf;
                this.maxrate = maxrate; this.bufsize = bufsize; this.audioBitrate = audioBitrate;
            }
        }

        /** Effective ladder — config when present, else the spec defaults. */
        public java.util.List<Rung> effectiveLadder() {
            if (ladder != null && !ladder.isEmpty()) return ladder;
            return java.util.List.of(
                    new Rung("360p", 360, 26, "800k", "1600k", "96k"),
                    new Rung("480p", 480, 25, "1400k", "2800k", "128k"),
                    new Rung("720p", 720, 23, "2800k", "5600k", "128k"),
                    new Rung("1080p", 1080, 23, "5000k", "10000k", "128k"));
        }

        /** Max source durations by asset type — enforced at ingest and re-checked by the worker. */
        @Getter @Setter
        public static class DurationCaps {
            private int videoClipSeconds = 90;
            private int filmSeconds = 600;
            private int videoSeconds = 600;
        }
    }

    @Getter @Setter
    public static class Limits {
        private long imageMaxBytes = 26_214_400L;    // 25 MB
        private long videoMaxBytes = 536_870_912L;   // 512 MB
        private long audioMaxBytes = 20_971_520L;    // 20 MB (voice notes, sound uploads)
        private long fileMaxBytes = 104_857_600L;    // 100 MB (documents/attachments)
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
        /** Rabbit consumer count for the media.process queue (one transcode at a time by default). */
        private int workerConcurrency = 1;
        /** Sweeper republish ceiling before an asset goes FAILED_PROCESSING. */
        private int maxAttempts = 3;
        /** PROCESSING age (since last update) before the sweeper intervenes. */
        private int stuckAfterSeconds = 3600;
        private long sweeperInitialDelayMs = 30_000;
        private long sweeperIntervalMs = 60_000;
    }

    @Getter @Setter
    public static class Ingest {
        /** Kill switch: false → multipart surfaces upload originals exactly as before the pipeline. */
        private boolean enabled = true;
        /** When false, video ingest stores original+poster only (no queue publish, no ladder). */
        private boolean videoAsyncEnabled = true;
        /** Per-role daily quota enforcement on the ingest path. */
        private boolean quotaEnabled = true;
    }

    @Getter @Setter
    public static class Security {
        /** Kill switch for magic-byte content verification on ingest. */
        private boolean magicCheckEnabled = true;
        /**
         * DEFAULT-BLOCKED: executable/script content is rejected on every surface —
         * by magic bytes (PE/ELF/Mach-O/class/shebang) AND by this extension
         * blocklist. Flip only with an explicit product reason.
         */
        private boolean allowExecutables = false;
        /** Extension blocklist applied when {@code allowExecutables=false} (lower-case, no dot). */
        private java.util.List<String> blockedExtensions = new java.util.ArrayList<>(java.util.List.of(
                "exe", "dll", "msi", "scr", "com", "pif", "cpl",
                "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "csh",
                "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta",
                "jar", "class", "apk", "app", "dmg", "deb", "rpm", "run", "elf"));
    }

    @Getter @Setter
    public static class Uploads {
        /** Where chunked-upload sessions spool before ingest (instance-local). */
        private String sessionDir = System.getProperty("java.io.tmpdir") + "/irc-upload-sessions";
        /** Server-declared chunk size clients must honor (last chunk may be smaller). */
        private long chunkBytes = 5 * 1024 * 1024;
        /** Sessions idle past this are swept (files + row). */
        private int sessionTtlHours = 24;
        /** Ceiling on concurrent open sessions per user. */
        private int maxOpenSessionsPerUser = 8;
    }

    /**
     * Read-side delivery policy. All fields default to today's behavior
     * (same-origin proxy URLs, no signing) so nothing changes until an
     * operator opts in via env.
     */
    @Getter @Setter
    public static class Serving {
        /**
         * CDN base URL (e.g. {@code https://cdn.example.com}). When set,
         * rendition URLs are rewritten at read time — historical rows
         * included, since they persist proxy-relative URLs.
         */
        private String cdnBase = "";
        /**
         * How the CDN reaches the bytes: {@code PROXY} — the CDN's origin is
         * this app, URLs keep the {@code /api/v1/media/…} path; {@code BUCKET}
         * — the CDN fronts the bucket's custom domain, URLs become
         * {@code {cdnBase}/{objectKey}} directly (no app hop).
         */
        private String cdnMode = "PROXY";
        /** Master switch for HMAC-signed playback URL enforcement. */
        private boolean signedUrlsEnabled = false;
        /** HMAC-SHA256 secret; signing is inert while blank. */
        private String signingSecret = "";
        /** Default lifetime of a signed playback URL. */
        private long signedUrlTtlSeconds = 3600;
        /**
         * Object-key prefixes that REQUIRE a valid signature to serve
         * (e.g. {@code media-private/}). Empty (default) → nothing is gated,
         * existing public URLs keep working.
         */
        private java.util.List<String> protectedPrefixes = new java.util.ArrayList<>();
    }

    /** Nullable per-surface overrides; null field → the surface's code default applies. */
    @Getter @Setter
    public static class SurfaceOverride {
        private Long imageMaxBytes;
        private Long videoMaxBytes;
        private Long audioMaxBytes;
        private Long fileMaxBytes;
        private Integer maxCount;
        private Integer maxDurationSeconds;
    }
}
