package ak.dev.irc.app.media.service;

import ak.dev.irc.app.media.config.MediaProperties;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CMAF/fMP4 HLS packaging of the already-encoded H.264 ladder. Each rung is
 * <b>remuxed</b> ({@code -c copy}) into an init segment + fMP4 media segments
 * + a variant playlist; a hand-written master playlist ties them together with
 * accurate {@code BANDWIDTH}/{@code RESOLUTION}/{@code CODECS} attributes.
 *
 * <p>Object layout (immutable, content-addressed by asset id):</p>
 * <pre>{@code
 * media/{assetId}/hls/master.m3u8
 * media/{assetId}/hls/480p/init.mp4
 * media/{assetId}/hls/480p/seg_000.m4s …
 * media/{assetId}/hls/480p/playlist.m3u8
 * }</pre>
 *
 * <p>Failure discipline: an ffmpeg/local-packaging failure returns {@code null}
 * (soft — the MP4 ladder is still fully playable and HLS is an upgrade, not a
 * requirement); a storage failure mid-upload <b>throws</b> so the worker's
 * transient-retry path re-runs the packaging (segment uploads are idempotent —
 * same keys, same bytes).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HlsPackagingService {

    /** All rungs encode H.264 High@4.1 + AAC-LC — one CODECS string fits all. */
    private static final String CODECS = "avc1.640029,mp4a.40.2";

    public static final String MASTER_MIME = "application/vnd.apple.mpegurl";
    private static final String SEGMENT_MIME = "video/iso.segment";

    private final FfmpegRunner ffmpeg;
    private final S3StorageService storage;
    private final MediaProperties props;

    /**
     * One ladder rung to package.
     *
     * @param file    local MP4 for this rung (caller owns the file)
     * @param maxrate the rung's encode maxrate string (e.g. {@code "2800k"})
     */
    public record RungInput(String label, Path file, Integer width, Integer height,
                            Long bytes, String maxrate, String audioBitrate) {}

    /** @param totalBytes every uploaded object (segments + playlists) summed */
    public record HlsResult(String masterKey, long totalBytes, int variantCount) {}

    /**
     * Package the given rungs and upload the tree.
     *
     * @param durationMs source duration when known — enables the accurate
     *                   {@code AVERAGE-BANDWIDTH} attribute
     * @return the result, or {@code null} when local packaging wasn't possible
     *         (missing ffmpeg, remux failure) — caller treats that as soft
     */
    public HlsResult packageLadder(UUID assetId, List<RungInput> rungs, Integer durationMs) {
        if (rungs == null || rungs.isEmpty() || !ffmpeg.ffmpegAvailable()) return null;

        List<RungInput> ordered = orderForMaster(rungs);
        StringBuilder master = new StringBuilder();
        master.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");

        long totalBytes = 0;
        int variants = 0;
        for (RungInput rung : ordered) {
            Path dir = null;
            try {
                dir = Files.createTempDirectory("media-hls-" + rung.label() + "-");
                if (!remuxToHls(rung, dir)) {
                    log.warn("[MEDIA-HLS] {} rung {} remux failed — HLS skipped", assetId, rung.label());
                    return null;   // partial masters help nobody; MP4 ladder still serves
                }
                totalBytes += uploadVariantDir(assetId, rung.label(), dir);
                master.append(streamInf(rung, durationMs))
                      .append(rung.label()).append("/playlist.m3u8\n");
                variants++;
            } catch (IOException io) {
                log.warn("[MEDIA-HLS] {} rung {} packaging I/O failure: {}", assetId, rung.label(), io.getMessage());
                return null;
            } finally {
                deleteRecursively(dir);
            }
        }
        if (variants == 0) return null;

        // Master uploads LAST — a reader can never resolve it to missing segments.
        String masterKey = "media/" + assetId + "/hls/master.m3u8";
        byte[] masterBytes = master.toString().getBytes(StandardCharsets.UTF_8);
        storage.putBytes(masterBytes, masterKey, MASTER_MIME);
        totalBytes += masterBytes.length;

        log.info("[MEDIA-HLS] {} packaged {} variant(s), {} bytes total", assetId, variants, totalBytes);
        return new HlsResult(masterKey, totalBytes, variants);
    }

    // ── pieces ───────────────────────────────────────────────────────────────

    /**
     * The start rung leads the master (players begin there before bandwidth
     * estimation kicks in — mid-ladder means fast start without a 1080p stall);
     * the rest follow ascending by pixel count.
     */
    private List<RungInput> orderForMaster(List<RungInput> rungs) {
        String startLabel = props.getVideo().getHls().getStartLabel();
        List<RungInput> out = new ArrayList<>(rungs);
        out.sort(Comparator.comparingLong(r ->
                r.width() == null || r.height() == null ? Long.MAX_VALUE
                        : (long) r.width() * r.height()));
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).label().equals(startLabel) && i > 0) {
                RungInput start = out.remove(i);
                out.add(0, start);
                break;
            }
        }
        return out;
    }

    private boolean remuxToHls(RungInput rung, Path dir) {
        int segSeconds = Math.max(1, props.getVideo().getHls().getSegmentSeconds());
        // Segment/playlist names are RELATIVE and ffmpeg runs inside {@code dir}:
        // the muxer writes segment paths into the playlist exactly as given, so
        // absolute temp paths here would end up inside the served manifest.
        List<String> cmd = List.of(
                ffmpeg.ffmpegBin(), "-y", "-nostdin", "-i", rung.file().toAbsolutePath().toString(),
                "-c", "copy",
                "-f", "hls",
                "-hls_time", String.valueOf(segSeconds),
                "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4",
                "-hls_flags", "independent_segments",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", "seg_%03d.m4s",
                "playlist.m3u8");
        FfmpegRunner.RunResult r = ffmpeg.run(cmd, props.getProcessing().getTimeoutSeconds(), dir);
        try {
            return r.ok() && Files.exists(dir.resolve("playlist.m3u8"))
                    && Files.size(dir.resolve("playlist.m3u8")) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Upload every file in the variant dir; the playlist goes last. */
    private long uploadVariantDir(UUID assetId, String label, Path dir) throws IOException {
        long bytes = 0;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        Path playlist = null;
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (name.endsWith(".m3u8")) { playlist = f; continue; }
            bytes += uploadOne(assetId, label, f);
        }
        if (playlist != null) bytes += uploadOne(assetId, label, playlist);
        return bytes;
    }

    private long uploadOne(UUID assetId, String label, Path f) throws IOException {
        String name = f.getFileName().toString();
        String key = "media/" + assetId + "/hls/" + label + "/" + name;
        storage.putFile(f, key, mimeFor(name));
        return Files.size(f);
    }

    private static String mimeFor(String name) {
        if (name.endsWith(".m3u8")) return MASTER_MIME;
        if (name.endsWith(".m4s")) return SEGMENT_MIME;
        if (name.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    /**
     * {@code BANDWIDTH} (required — peak) from the rung's encode maxrate plus
     * audio and container overhead; {@code AVERAGE-BANDWIDTH} from the actual
     * file size when the duration is known — the truthful number ABR heuristics
     * prefer.
     */
    private static String streamInf(RungInput rung, Integer durationMs) {
        long peak = (long) ((bitsPerSecond(rung.maxrate(), 5_000_000L)
                + bitsPerSecond(rung.audioBitrate(), 128_000L)) * 1.1);
        StringBuilder sb = new StringBuilder("#EXT-X-STREAM-INF:BANDWIDTH=").append(peak);
        if (durationMs != null && durationMs > 0 && rung.bytes() != null && rung.bytes() > 0) {
            long avg = rung.bytes() * 8L * 1000L / durationMs;
            sb.append(",AVERAGE-BANDWIDTH=").append(avg);
        }
        if (rung.width() != null && rung.height() != null) {
            sb.append(",RESOLUTION=").append(rung.width()).append('x').append(rung.height());
        }
        sb.append(",CODECS=\"").append(CODECS).append("\"\n");
        return sb.toString();
    }

    /** Parse ffmpeg bitrate strings ({@code 800k}, {@code 5000k}, {@code 2m}). */
    private static long bitsPerSecond(String rate, long fallback) {
        if (rate == null || rate.isBlank()) return fallback;
        try {
            String s = rate.trim().toLowerCase(Locale.ROOT);
            if (s.endsWith("k")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000);
            if (s.endsWith("m")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) Files.deleteIfExists(f);
        } catch (IOException ignored) { }
        try { Files.deleteIfExists(dir); } catch (IOException ignored) { }
    }
}
