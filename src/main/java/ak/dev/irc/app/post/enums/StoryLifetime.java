package ak.dev.irc.app.post.enums;

import java.time.Duration;

/**
 * How long a story stays visible before auto-deletion.
 *
 * <p>The author picks one at create time; the chosen value becomes the
 * Cassandra row's per-write TTL (via {@code INSERT … USING TTL}), so the
 * row tombstones itself when the window elapses — no scheduled cleanup
 * job, no read-time filtering, no orphan rows.</p>
 *
 * <p>Default is {@link #H24}: matches the Instagram-style 24h ephemeral
 * story expectation and remains the value used when the client omits
 * the field entirely (legacy clients, anonymous-default).</p>
 */
public enum StoryLifetime {
    H8 (Duration.ofHours(8)),
    H16(Duration.ofHours(16)),
    H24(Duration.ofHours(24));

    /** Convenience alias — the default lifetime when none is supplied. */
    public static final StoryLifetime DEFAULT = H24;

    private final Duration duration;

    StoryLifetime(Duration duration) { this.duration = duration; }

    public Duration duration() { return duration; }
    public int      hours()    { return (int) duration.toHours(); }
    /** Seconds form — the unit Cassandra's {@code USING TTL} expects. */
    public int      ttlSeconds() { return (int) duration.getSeconds(); }

    /**
     * Lenient parse for client input — accepts the number of hours
     * directly (8 / 16 / 24) or the enum name (H8 / H16 / H24, case
     * insensitive). Any other value, {@code null}, or a parse failure
     * resolves to {@link #DEFAULT}. The endpoint never rejects on a
     * bad lifetime — it just falls back to 24h.
     */
    public static StoryLifetime fromHours(Integer hours) {
        if (hours == null) return DEFAULT;
        return switch (hours) {
            case 8  -> H8;
            case 16 -> H16;
            case 24 -> H24;
            default -> DEFAULT;
        };
    }

    public static StoryLifetime fromString(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        String t = s.trim().toUpperCase();
        try { return StoryLifetime.valueOf(t); }
        catch (IllegalArgumentException ignore) { /* fall through */ }
        try { return fromHours(Integer.parseInt(t)); }
        catch (NumberFormatException ignore) { return DEFAULT; }
    }
}
