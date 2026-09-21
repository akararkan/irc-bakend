package ak.dev.irc.app.media.enums;

import java.util.Locale;
import java.util.Map;

/**
 * Coarse classification of an uploaded file, derived from the declared content
 * type with an extension fallback. Drives per-surface allow-lists, size caps
 * and the processing branch (image sync / video async / passthrough).
 */
public enum MediaKind {
    IMAGE, VIDEO, AUDIO, FILE;

    private static final Map<String, MediaKind> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", IMAGE), Map.entry("jpeg", IMAGE), Map.entry("png", IMAGE),
            Map.entry("gif", IMAGE), Map.entry("webp", IMAGE), Map.entry("bmp", IMAGE),
            Map.entry("heic", IMAGE), Map.entry("heif", IMAGE),
            Map.entry("mp4", VIDEO), Map.entry("mov", VIDEO), Map.entry("webm", VIDEO),
            Map.entry("mkv", VIDEO), Map.entry("avi", VIDEO), Map.entry("m4v", VIDEO),
            Map.entry("mp3", AUDIO), Map.entry("m4a", AUDIO), Map.entry("aac", AUDIO),
            Map.entry("ogg", AUDIO), Map.entry("oga", AUDIO), Map.entry("wav", AUDIO),
            Map.entry("weba", AUDIO), Map.entry("flac", AUDIO));

    public static MediaKind classify(String contentType, String filename) {
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.startsWith("image/")) return IMAGE;
            if (ct.startsWith("video/")) return VIDEO;
            if (ct.startsWith("audio/")) return AUDIO;
        }
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                MediaKind byExt = BY_EXTENSION.get(
                        filename.substring(dot + 1).toLowerCase(Locale.ROOT));
                if (byExt != null) return byExt;
            }
        }
        return FILE;
    }
}
