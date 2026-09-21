package ak.dev.irc.app.moderation;

import ak.dev.irc.app.moderation.enums.FallbackPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the {@code app.moderation.*} block — the <em>bootstrap</em> defaults for
 * the automated text-moderation system (MODERATION_ROADMAP.md §20).
 *
 * <p>Anything an admin can retune (thresholds, hold durations, fallback policy,
 * per-entity enablement) is layered on top of these by
 * {@code ModerationSettingsService}, which reads the {@code moderation_settings}
 * table. That is what lets sensitivity change without a redeploy of either
 * service (§8.1). Values here are only what a fresh database starts with.</p>
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.moderation")
public class ModerationProperties {

    /**
     * Master switch. {@code false} bypasses the model entirely — the blocklist
     * still runs, and everything publishes as it did before this system existed.
     * The local-testing escape hatch, exactly parallel to
     * {@code app.security.permit-all}: never disable it in production.
     */
    private boolean enabled = true;

    /**
     * Fields with fewer whitespace-separated words than this are not sent to the
     * text model at all — the blocklist alone screens them. Empirically measured
     * (2026-09-01, artifact v4): below ~4 words the classifier emits noise, not
     * signal — unseen English words collapse to an identical constant vector
     * ("Hot" and "Nice" both score toxic 0.6336 to four decimals), and short
     * benign Kurdish greetings score 0.99+, indistinguishable from actual slurs.
     * Scoring noise against thresholds only manufactures false holds. Runtime
     * override: {@code text.min-scorable-words}. Set 0 to score everything.
     */
    private int minScorableWords = 4;

    private final Inference inference = new Inference();
    private final Training training = new Training();
    private final Retrain retrain = new Retrain();
    private final LiveChat liveChat = new LiveChat();
    private final Image image = new Image();

    /**
     * Per-label bootstrap bands, keyed by lowercase label name. Filled from yaml;
     * {@link #defaultThresholds()} supplies the roadmap's §8.1 starting values for
     * anything yaml omits.
     */
    private Map<String, Band> thresholds = new LinkedHashMap<>();

    /** Per-entity-type hold ceiling in milliseconds, keyed by lowercase type name. */
    private Map<String, Long> holdMs = new LinkedHashMap<>();

    /** Per-entity-type inline scoring budget in milliseconds, keyed by lowercase type name. */
    private Map<String, Long> inlineMs = new LinkedHashMap<>();

    /** Per-entity-type fallback policy ({@code FAIL_CLOSED} / {@code FAIL_OPEN_SHADOW}). */
    private Map<String, String> fallback = new LinkedHashMap<>();

    /** Per-entity-type enablement; absent means enabled. */
    private Map<String, Boolean> entityEnabled = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Band {
        /** At/above this, the content needs a human. */
        private double low;
        /** At/above this, the content is auto-blocked. */
        private double high;

        public Band() {
        }

        public Band(double low, double high) {
            this.low = low;
            this.high = high;
        }
    }

    @Getter
    @Setter
    public static class Inference {
        private String baseUrl = "http://localhost:8000";
        /** Shared secret sent as {@code X-API-Key}; empty disables the header. */
        private String apiKey = "";
        private long connectTimeoutMs = 1000;
        /** Ceiling for a single HTTP call, regardless of the per-entity inline budget. */
        private long timeoutMs = 5000;
        /** Attempts per call, including the first (roadmap §7.4: max-attempts 2). */
        private int maxAttempts = 2;
        private long retryBackoffMs = 200;
        /** Circuit breaker (§7.4): consecutive-failure window and cool-down. */
        private int circuitWindow = 20;
        private int circuitFailureRatePercent = 50;
        private long circuitOpenMs = 10_000;
        /** Fields per {@code /v1/score/batch} call; must not exceed the container's MAX_BATCH_ITEMS. */
        private int maxBatchItems = 32;
    }

    @Getter
    @Setter
    public static class Training {
        private String baseUrl = "http://localhost:8001";
        private String apiKey = "";
        private long timeoutMs = 30_000;
        /**
         * URL the training container calls back when a job finishes. It runs in
         * Docker, so {@code host.docker.internal} is how it reaches a backend on
         * the host. Empty disables the callback and leaves polling as the only
         * completion signal.
         */
        private String callbackUrl = "";
        /** Shared secret the callback must present; empty accepts any caller. */
        private String callbackToken = "";
        /** How many examples to ship per training request. */
        private int maxExamples = 20_000;
    }

    @Getter
    @Setter
    public static class Retrain {
        /**
         * Promotion gate (§12.4): a candidate may not drop more than this many
         * F1 points on any label versus the currently active version.
         */
        private double maxF1Drop = 0.02;
        /** Never let a retrain auto-promote itself (§12.4). */
        private boolean requireHumanPromote = true;
        /** Below this, {@code POST /model/retrain} refuses — too little signal to learn from. */
        private int minExamples = 20;
        /** How often Spring Boot polls a running job for status, in milliseconds. */
        private long pollIntervalMs = 15_000;
    }

    @Getter
    @Setter
    public static class LiveChat {
        /** The §6 rolling buffer: how long a message is withheld before release. */
        private long bufferMs = 3_000;
        /**
         * Borderline live-chat lines default to hidden (§6). For live chat a false
         * negative is far worse than a dropped message — there is no review queue
         * that catches up later.
         */
        private boolean borderlineHidden = true;
    }

    /**
     * NSFW image screening (docs/moderation/image-moderation.md). Like the rest
     * of this file these are bootstrap defaults: {@code image.*} keys in
     * {@code moderation_settings} override the thresholds/fallback/enabled at
     * runtime. The master {@link #enabled} switch above also gates this — with
     * MODERATION_ENABLED=false no image is ever scored.
     */
    @Getter
    @Setter
    public static class Image {
        private boolean enabled = true;
        /** The scorer container (docker-compose: image-inference). */
        private String baseUrl = "http://localhost:8002";
        /** Shared secret sent as {@code X-API-Key}; must match IMAGE_INFERENCE_API_KEY. */
        private String apiKey = "";
        private long connectTimeoutMs = 1000;
        /** Payloads are pre-scaled to thumbnails before sending, so calls are fast. */
        private long timeoutMs = 4000;
        private int maxAttempts = 2;
        private long retryBackoffMs = 200;
        private int circuitWindow = 20;
        private int circuitFailureRatePercent = 50;
        private long circuitOpenMs = 10_000;
        /**
         * At/above this nsfw score the upload is rejected outright. Tuned for
         * precision: the target is pornographic/explicit content ONLY —
         * portraits, beach photos, and other skin-adjacent-but-clothed images
         * must pass. On this checkpoint real explicit content scores ≥0.95
         * almost always, so 0.90 blocks porn while sparing borderline benign.
         */
        private double blockThreshold = 0.90;
        /**
         * At/above this (and below block) the image publishes but lands in the
         * review queue. Kept high (narrow band) for the same precision goal —
         * only near-miss explicit content deserves human minutes.
         */
        private double reviewThreshold = 0.80;
        /**
         * Longest edge the image is scaled down to before being base64'd to the
         * scorer. The model resizes to 224×224 internally, so shipping a 5MB
         * original buys nothing over a ~50KB thumbnail — this is the single
         * biggest latency lever on the upload path. 0 sends originals verbatim.
         */
        private int prescaleMaxDim = 512;
        /**
         * What happens when the scorer is unreachable: {@code FAIL_OPEN_SHADOW}
         * (default) publishes and queues the image for later review;
         * {@code FAIL_CLOSED} refuses the upload with a 503. Fail-open by
         * default because a scorer outage must not take image uploads down with
         * it — flip to FAIL_CLOSED for a moderation-first posture.
         */
        private String fallback = FallbackPolicy.FAIL_OPEN_SHADOW.name();
        /** Also screen the extracted poster frame of every uploaded video. */
        private boolean scoreVideoPosters = true;
    }

    /** Roadmap §8.1 starting bands, used for any label yaml does not pin. */
    public static Map<String, Band> defaultThresholds() {
        Map<String, Band> defaults = new LinkedHashMap<>();
        defaults.put("toxic", new Band(0.30, 0.80));
        defaults.put("severe_toxic", new Band(0.20, 0.60));
        defaults.put("obscene", new Band(0.30, 0.80));
        // Lower bar on both: a false negative on a threat or on identity hate is
        // materially worse than a false positive.
        defaults.put("threat", new Band(0.15, 0.50));
        defaults.put("insult", new Band(0.30, 0.80));
        defaults.put("identity_hate", new Band(0.15, 0.55));
        return defaults;
    }
}
