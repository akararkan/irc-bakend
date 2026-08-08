package ak.dev.irc.app.moderation.engine;

import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.entity.ModerationSetting;
import ak.dev.irc.app.moderation.enums.FallbackPolicy;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.repository.ModerationSettingRepository;
import ak.dev.irc.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves every runtime-tunable moderation knob: DB override first, then the
 * {@code app.moderation.*} bootstrap default (MODERATION_ROADMAP.md §8.1, §20).
 *
 * <p>Caching follows the {@code FeedTuningService} template — an in-process
 * snapshot with a short TTL plus an explicit {@link #invalidate()} on write, so
 * the read path costs zero queries and an admin edit is live immediately on the
 * node that served the PATCH. On a multi-node deploy other nodes converge within
 * {@link #CACHE_TTL_MS}; that is an acceptable lag for threshold tuning and is
 * called out in docs/moderation/admin-guide.md.</p>
 *
 * <p>Resolution <em>fails safe, not fail-open</em>: if the settings table cannot
 * be read the bootstrap defaults are used, which still enforce moderation. That
 * is the opposite choice from {@code PlatformKeywordService.blockOrFlag} (which
 * fails open so an infra blip cannot block posting) and it is deliberate — here
 * a failure degrades to "the roadmap's default thresholds", not to "no
 * thresholds at all".</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationSettingsService {

    private static final long CACHE_TTL_MS = 30_000;
    private static final String LOADED_SENTINEL = "__loaded";

    // ── key namespace (documented in docs/moderation/admin-guide.md) ────
    public static final String KEY_ENABLED = "enabled";
    private static final String PREFIX_ENABLED = "enabled.";
    private static final String PREFIX_THRESHOLD = "threshold.";
    private static final String PREFIX_HOLD = "hold.";
    private static final String PREFIX_INLINE = "inline.";
    private static final String PREFIX_FALLBACK = "fallback.";
    public static final String KEY_LIVECHAT_BUFFER_MS = "livechat.buffer.ms";
    public static final String KEY_LIVECHAT_BORDERLINE_HIDDEN = "livechat.borderline.hidden";
    public static final String KEY_RETRAIN_MAX_F1_DROP = "retrain.max-f1-drop";
    public static final String KEY_RETRAIN_REQUIRE_HUMAN_PROMOTE = "retrain.require-human-promote";

    private final ModerationSettingRepository repository;
    private final ModerationProperties properties;

    private volatile Map<String, String> cached = Map.of();
    private volatile long cachedAt = 0L;

    // ── resolved reads ──────────────────────────────────────────────────

    /** Master switch AND per-type switch; both must be on. */
    public boolean enabled(ModeratedEntityType type) {
        if (!bool(KEY_ENABLED, properties.isEnabled())) return false;
        Boolean yamlOverride = properties.getEntityEnabled().get(type.key());
        return bool(PREFIX_ENABLED + type.key(), yamlOverride == null || yamlOverride);
    }

    public boolean globallyEnabled() {
        return bool(KEY_ENABLED, properties.isEnabled());
    }

    /**
     * Per-entity-type bands. A type-specific override wins over the global band
     * for that label — §8.1's example being that academic critique in a Research
     * body legitimately tolerates a higher {@code insult} bar than a public
     * comment, while {@code threat} and {@code identity_hate} stay strict
     * everywhere.
     */
    public ModerationThresholds thresholds(ModeratedEntityType type) {
        Map<String, String> overrides = snapshot();
        Map<ModerationLabel, ModerationThresholds.Band> bands =
                new EnumMap<>(ModerationLabel.class);

        for (ModerationLabel label : ModerationLabel.values()) {
            ModerationProperties.Band bootstrap = properties.getThresholds().get(label.wire());
            if (bootstrap == null) {
                bootstrap = ModerationProperties.defaultThresholds().get(label.wire());
            }
            double low = bootstrap == null ? 0.30 : bootstrap.getLow();
            double high = bootstrap == null ? 0.80 : bootstrap.getHigh();

            low = readDouble(overrides, PREFIX_THRESHOLD + label.wire() + ".low", low);
            high = readDouble(overrides, PREFIX_THRESHOLD + label.wire() + ".high", high);
            low = readDouble(overrides, PREFIX_THRESHOLD + type.key() + "." + label.wire() + ".low", low);
            high = readDouble(overrides, PREFIX_THRESHOLD + type.key() + "." + label.wire() + ".high", high);

            bands.put(label, new ModerationThresholds.Band(clamp(low), clamp(high)));
        }
        return ModerationThresholds.of(bands);
    }

    /** How long the unit may sit PENDING before the fallback policy applies (§5.3). */
    public Duration holdCeiling(ModeratedEntityType type) {
        Long yaml = properties.getHoldMs().get(type.key());
        long fallback = yaml != null ? yaml : type.defaultHoldCeiling().toMillis();
        return Duration.ofMillis(Math.max(500, readLong(snapshot(), PREFIX_HOLD + type.key() + ".ms", fallback)));
    }

    /**
     * Timeout for the synchronous attempt on the request thread. Clamped below
     * the hold ceiling: §5.3 requires the ceiling to always exceed the client
     * timeout, otherwise the sweeper fires while the request is still waiting and
     * two code paths decide the same case.
     */
    public Duration inlineBudget(ModeratedEntityType type) {
        Long yaml = properties.getInlineMs().get(type.key());
        long fallback = yaml != null ? yaml : type.defaultInlineBudget().toMillis();
        long resolved = readLong(snapshot(), PREFIX_INLINE + type.key() + ".ms", fallback);
        long ceiling = holdCeiling(type).toMillis();
        return Duration.ofMillis(Math.max(100, Math.min(resolved, Math.max(200, ceiling / 2))));
    }

    public FallbackPolicy fallback(ModeratedEntityType type) {
        String yaml = properties.getFallback().get(type.key());
        FallbackPolicy bootstrap = parsePolicy(yaml, type.defaultFallback());
        return parsePolicy(snapshot().get(PREFIX_FALLBACK + type.key()), bootstrap);
    }

    public long liveChatBufferMs() {
        return readLong(snapshot(), KEY_LIVECHAT_BUFFER_MS, properties.getLiveChat().getBufferMs());
    }

    public boolean liveChatBorderlineHidden() {
        return bool(KEY_LIVECHAT_BORDERLINE_HIDDEN, properties.getLiveChat().isBorderlineHidden());
    }

    public double retrainMaxF1Drop() {
        return readDouble(snapshot(), KEY_RETRAIN_MAX_F1_DROP, properties.getRetrain().getMaxF1Drop());
    }

    public boolean retrainRequiresHumanPromote() {
        return bool(KEY_RETRAIN_REQUIRE_HUMAN_PROMOTE, properties.getRetrain().isRequireHumanPromote());
    }

    // ── admin writes ────────────────────────────────────────────────────

    /** Every stored override, for the settings screen. */
    @Transactional(readOnly = true)
    public Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>(snapshot());
        out.remove(LOADED_SENTINEL);
        return out;
    }

    /** The fully resolved effective view, so admins see what is actually in force. */
    public Map<String, Object> effective() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_ENABLED, globallyEnabled());
        out.put(KEY_LIVECHAT_BUFFER_MS, liveChatBufferMs());
        out.put(KEY_LIVECHAT_BORDERLINE_HIDDEN, liveChatBorderlineHidden());
        out.put(KEY_RETRAIN_MAX_F1_DROP, retrainMaxF1Drop());
        out.put(KEY_RETRAIN_REQUIRE_HUMAN_PROMOTE, retrainRequiresHumanPromote());

        Map<String, Object> byType = new LinkedHashMap<>();
        for (ModeratedEntityType type : ModeratedEntityType.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("enabled", enabled(type));
            entry.put("holdMs", holdCeiling(type).toMillis());
            entry.put("inlineMs", inlineBudget(type).toMillis());
            entry.put("fallback", fallback(type).name());
            entry.put("ephemeral", type.ephemeral());
            Map<String, Object> bands = new LinkedHashMap<>();
            ModerationThresholds thresholds = thresholds(type);
            for (ModerationLabel label : ModerationLabel.values()) {
                ModerationThresholds.Band band = thresholds.band(label);
                bands.put(label.wire(), Map.of("low", band.low(), "high", band.high()));
            }
            entry.put("thresholds", bands);
            byType.put(type.key(), entry);
        }
        out.put("entityTypes", byType);
        return out;
    }

    @Transactional
    public void put(String key, String value) {
        String normalized = normalizeKey(key);
        repository.save(ModerationSetting.builder()
                .settingKey(normalized)
                .settingValue(value)
                .updatedBy(SecurityUtils.getCurrentUserId().orElse(null))
                .build());
        invalidate();
    }

    @Transactional
    public void putAll(Map<String, String> entries) {
        entries.forEach((key, value) -> repository.save(ModerationSetting.builder()
                .settingKey(normalizeKey(key))
                .settingValue(value)
                .updatedBy(SecurityUtils.getCurrentUserId().orElse(null))
                .build()));
        invalidate();
    }

    @Transactional
    public void remove(String key) {
        repository.deleteById(normalizeKey(key));
        invalidate();
    }

    /** Drops every override, returning the platform to the yaml bootstrap values. */
    @Transactional
    public void resetAll() {
        repository.deleteAll();
        invalidate();
    }

    public void invalidate() {
        cachedAt = 0L;
        cached = Map.of();
    }

    // ── internals ───────────────────────────────────────────────────────

    private Map<String, String> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, String> local = cached;
        if (now - cachedAt < CACHE_TTL_MS && !local.isEmpty()) {
            return local;
        }
        try {
            List<ModerationSetting> rows = repository.findAll();
            Map<String, String> fresh = new LinkedHashMap<>(rows.size() * 2);
            for (ModerationSetting row : rows) {
                fresh.put(row.getSettingKey(), row.getSettingValue());
            }
            // Sentinel so an empty override table does not re-query on every read —
            // the cache freshness check keys off "is the map non-empty", and with
            // zero overrides (the common case) it never would be.
            fresh.putIfAbsent(LOADED_SENTINEL, "1");
            cached = fresh;
            cachedAt = now;
            return fresh;
        } catch (Exception ex) {
            log.debug("[MODERATION] settings read failed, using bootstrap defaults: {}", ex.getMessage());
            return local.isEmpty() ? Map.of() : local;
        }
    }

    private boolean bool(String key, boolean fallback) {
        String raw = snapshot().get(key);
        if (raw == null) return fallback;
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    private static double readDouble(Map<String, String> map, String key, double fallback) {
        String raw = map.get(key);
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long readLong(Map<String, String> map, String key, long fallback) {
        String raw = map.get(key);
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static FallbackPolicy parsePolicy(String raw, FallbackPolicy fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return FallbackPolicy.valueOf(raw.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0) return 0;
        return Math.min(value, 1.0);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase();
    }
}
