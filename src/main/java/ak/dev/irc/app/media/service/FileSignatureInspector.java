package ak.dev.irc.app.media.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Magic-byte (file signature) inspection — the server-side truth about what an
 * uploaded file actually is, independent of the client-declared MIME type and
 * filename extension (both trivially spoofable). Pure JDK, no Tika: we only
 * need family-level detection (image / video / audio / pdf / archive /
 * executable), not full MIME resolution.
 *
 * <p>Two verdicts matter to the ingest gate:</p>
 * <ul>
 *   <li>{@link Sniff#executable()} — the bytes are native/script executable
 *       code (PE, ELF, Mach-O, Java class, shebang script). Blocked by
 *       default regardless of what the file claims to be.</li>
 *   <li>{@link Sniff#family()} — coarse detected family, used to reject
 *       spoofs like an EXE renamed to .jpg with an image/jpeg header.</li>
 * </ul>
 */
@Component
public class FileSignatureInspector {

    /** How many leading bytes we need for every signature checked here. */
    public static final int HEADER_LEN = 64;

    public enum Family { IMAGE, VIDEO, AUDIO, PDF, ARCHIVE, TEXT, UNKNOWN }

    public record Sniff(Family family, boolean executable, String detected) {}

    /** Read up to {@link #HEADER_LEN} leading bytes without consuming the caller's stream. */
    public byte[] readHeader(InputStream in) throws IOException {
        return in.readNBytes(HEADER_LEN);
    }

    public Sniff inspect(byte[] h) {
        if (h == null || h.length < 4) return new Sniff(Family.UNKNOWN, false, null);

        // ── Executable code ────────────────────────────────────────────────
        if (h[0] == 'M' && h[1] == 'Z') return exec("pe/exe");                    // Windows PE
        if (u(h[0]) == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return exec("elf");
        int be = beInt(h, 0);
        if (be == 0xFEEDFACE || be == 0xFEEDFACF || be == 0xCEFAEDFE || be == 0xCFFAEDFE
                || be == 0xCAFEBABE) {
            // Mach-O (both endians, 32/64) and fat binaries; 0xCAFEBABE is also
            // the Java .class magic — both are executable code, both blocked.
            return exec("mach-o/class");
        }
        if (h[0] == '#' && h[1] == '!') return exec("script-shebang");

        // ── Images ─────────────────────────────────────────────────────────
        if (u(h[0]) == 0xFF && u(h[1]) == 0xD8 && u(h[2]) == 0xFF) return fam(Family.IMAGE, "jpeg");
        if (u(h[0]) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return fam(Family.IMAGE, "png");
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return fam(Family.IMAGE, "gif");
        if (h[0] == 'B' && h[1] == 'M') return fam(Family.IMAGE, "bmp");
        if ((u(h[0]) == 0x49 && u(h[1]) == 0x49 && u(h[2]) == 0x2A && u(h[3]) == 0x00)
                || (u(h[0]) == 0x4D && u(h[1]) == 0x4D && u(h[2]) == 0x00 && u(h[3]) == 0x2A)) {
            return fam(Family.IMAGE, "tiff");
        }
        if (isRiff(h, "WEBP")) return fam(Family.IMAGE, "webp");

        // ── ISO base-media (ftyp): mp4/mov/m4a/heic — brand decides family ──
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String brand = new String(h, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.startsWith("heix")
                    || brand.startsWith("avif") || brand.startsWith("mif1")) {
                return fam(Family.IMAGE, "heif");
            }
            if (brand.startsWith("m4a")) return fam(Family.AUDIO, "m4a");
            return fam(Family.VIDEO, "mp4/" + brand.trim());
        }

        // ── Video ──────────────────────────────────────────────────────────
        if (u(h[0]) == 0x1A && u(h[1]) == 0x45 && u(h[2]) == 0xDF && u(h[3]) == 0xA3) {
            return fam(Family.VIDEO, "webm/mkv");   // EBML
        }
        if (isRiff(h, "AVI ")) return fam(Family.VIDEO, "avi");

        // ── Audio ──────────────────────────────────────────────────────────
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return fam(Family.AUDIO, "mp3");
        if (u(h[0]) == 0xFF && (u(h[1]) & 0xE0) == 0xE0) return fam(Family.AUDIO, "mpeg-audio");
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return fam(Family.AUDIO, "ogg");
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return fam(Family.AUDIO, "flac");
        if (isRiff(h, "WAVE")) return fam(Family.AUDIO, "wav");

        // ── Documents / archives ───────────────────────────────────────────
        if (h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return fam(Family.PDF, "pdf");
        if (h[0] == 'P' && h[1] == 'K') return fam(Family.ARCHIVE, "zip");   // also docx/xlsx/pptx/jar/apk
        if (u(h[0]) == 0x1F && u(h[1]) == 0x8B) return fam(Family.ARCHIVE, "gzip");
        if (h[0] == '7' && h[1] == 'z' && u(h[2]) == 0xBC && u(h[3]) == 0xAF) return fam(Family.ARCHIVE, "7z");
        if (h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!') return fam(Family.ARCHIVE, "rar");
        if (u(h[0]) == 0xD0 && u(h[1]) == 0xCF && u(h[2]) == 0x11 && u(h[3]) == 0xE0) {
            return fam(Family.ARCHIVE, "ole2");     // legacy .doc/.xls/.ppt/.msi container
        }

        return new Sniff(Family.UNKNOWN, false, null);
    }

    private static Sniff exec(String what)             { return new Sniff(Family.UNKNOWN, true, what); }
    private static Sniff fam(Family f, String what)    { return new Sniff(f, false, what); }
    private static int u(byte b)                       { return b & 0xFF; }

    private static int beInt(byte[] h, int off) {
        if (h.length < off + 4) return 0;
        return (u(h[off]) << 24) | (u(h[off + 1]) << 16) | (u(h[off + 2]) << 8) | u(h[off + 3]);
    }

    private static boolean isRiff(byte[] h, String subtype) {
        if (h.length < 12 || h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return false;
        return subtype.equals(new String(h, 8, 4, StandardCharsets.US_ASCII));
    }
}
