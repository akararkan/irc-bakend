package ak.dev.irc.app.moderation.enums;

import java.time.Duration;
import java.util.Optional;

/**
 * Every content unit that carries user text (MODERATION_ROADMAP.md §2). One
 * pipeline serves all of them; only the hold duration, the inline latency
 * budget and the fallback policy differ per type (§5.3, §20).
 *
 * <p>The constants here are <em>bootstrap</em> defaults. Anything an admin can
 * retune lives in {@code moderation_settings} and is read through
 * {@code ModerationSettingsService} — these values are only what the platform
 * starts with on a fresh database.</p>
 *
 * <p>{@link #ephemeral()} marks the real-time exception (§6): live chat has no
 * durable row to hold, so it is scored inline against a short budget and either
 * released or dropped. There is no queue path and no SLA sweeper for it.</p>
 */
public enum ModeratedEntityType {

    //                        inlineBudget      holdCeiling        fallback                    ephemeral
    POST(                     Duration.ofSeconds(2),  Duration.ofSeconds(30), FallbackPolicy.FAIL_CLOSED,      false),
    POST_COMMENT(             Duration.ofMillis(500), Duration.ofSeconds(10), FallbackPolicy.FAIL_CLOSED,      false),
    STORY(                    Duration.ofSeconds(1),  Duration.ofSeconds(15), FallbackPolicy.FAIL_OPEN_SHADOW, false),
    STORY_POLL(               Duration.ofSeconds(1),  Duration.ofSeconds(15), FallbackPolicy.FAIL_OPEN_SHADOW, false),
    RESEARCH(                 Duration.ofSeconds(5),  Duration.ofSeconds(60), FallbackPolicy.FAIL_CLOSED,      false),
    RESEARCH_COMMENT(         Duration.ofMillis(500), Duration.ofSeconds(10), FallbackPolicy.FAIL_CLOSED,      false),
    QNA_QUESTION(             Duration.ofSeconds(2),  Duration.ofSeconds(30), FallbackPolicy.FAIL_CLOSED,      false),
    QNA_ANSWER(               Duration.ofSeconds(2),  Duration.ofSeconds(30), FallbackPolicy.FAIL_CLOSED,      false),
    CHAT_MESSAGE(             Duration.ofMillis(500), Duration.ofSeconds(10), FallbackPolicy.FAIL_CLOSED,      false),
    CHANNEL(                  Duration.ofSeconds(2),  Duration.ofSeconds(30), FallbackPolicy.FAIL_CLOSED,      false),
    /** Stream title/description — normal §5 pipeline, not the real-time path (§6). */
    STREAM_META(              Duration.ofSeconds(2),  Duration.ofSeconds(30), FallbackPolicy.FAIL_CLOSED,      false),
    /** Live-stream chat line — the §6 real-time exception. Never queued. */
    LIVE_CHAT(                Duration.ofMillis(300), Duration.ofSeconds(5),  FallbackPolicy.FAIL_CLOSED,      true),
    /** Share caption / media alt-text / highlight title — short, low-volume text. */
    CONTENT_ANNOTATION(       Duration.ofMillis(500), Duration.ofSeconds(10), FallbackPolicy.FAIL_CLOSED,      false);

    private final Duration defaultInlineBudget;
    private final Duration defaultHoldCeiling;
    private final FallbackPolicy defaultFallback;
    private final boolean ephemeral;

    ModeratedEntityType(Duration defaultInlineBudget, Duration defaultHoldCeiling,
                        FallbackPolicy defaultFallback, boolean ephemeral) {
        this.defaultInlineBudget = defaultInlineBudget;
        this.defaultHoldCeiling = defaultHoldCeiling;
        this.defaultFallback = defaultFallback;
        this.ephemeral = ephemeral;
    }

    /** Timeout budget for the synchronous scoring attempt on the request thread (§5.3). */
    public Duration defaultInlineBudget() {
        return defaultInlineBudget;
    }

    /** Hard ceiling on how long the unit may sit {@code PENDING} (§5.3). */
    public Duration defaultHoldCeiling() {
        return defaultHoldCeiling;
    }

    public FallbackPolicy defaultFallback() {
        return defaultFallback;
    }

    /** No durable row exists to hold — decide inline or drop (§6). */
    public boolean ephemeral() {
        return ephemeral;
    }

    /** Lowercase key used in {@code moderation_settings} and {@code app.moderation.*} yaml. */
    public String key() {
        return name().toLowerCase();
    }

    public static Optional<ModeratedEntityType> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
