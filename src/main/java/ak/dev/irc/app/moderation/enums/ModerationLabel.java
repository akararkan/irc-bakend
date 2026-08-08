package ak.dev.irc.app.moderation.enums;

import java.util.Map;
import java.util.Optional;

/**
 * The six sigmoid heads of the fine-tuned toxic-bert classifier
 * (MODERATION_ROADMAP.md §0.1). Multi-label, not multi-class: a text can trip
 * several of these at once, or none.
 *
 * <p>{@link #wire()} is the exact JSON key the inference container returns —
 * keep it in lockstep with {@code LABELS} in {@code docs/model-inference/app/main.py}
 * and {@code docs/model-training/app/main.py}. The enum name is what the admin
 * API and the settings keys use.</p>
 */
public enum ModerationLabel {

    TOXIC("toxic"),
    SEVERE_TOXIC("severe_toxic"),
    OBSCENE("obscene"),
    /** Deliberately gets the strictest default bands — a missed threat costs far
     *  more than a false positive (§8.1). */
    THREAT("threat"),
    INSULT("insult"),
    IDENTITY_HATE("identity_hate");

    private final String wire;

    ModerationLabel(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    private static final Map<String, ModerationLabel> BY_WIRE = Map.of(
            "toxic", TOXIC,
            "severe_toxic", SEVERE_TOXIC,
            "severe_toxicity", SEVERE_TOXIC,
            "obscene", OBSCENE,
            "threat", THREAT,
            "insult", INSULT,
            "identity_hate", IDENTITY_HATE,
            "identity_attack", IDENTITY_HATE,
            "toxicity", TOXIC);

    /**
     * Lenient lookup. An artifact trained from a different base checkpoint may
     * name a head {@code toxicity} or {@code identity_attack}; scoring on an
     * unknown key must not blow up the decision engine, so this returns empty
     * and the caller ignores that head rather than throwing.
     */
    public static Optional<ModerationLabel> fromWire(String raw) {
        if (raw == null) return Optional.empty();
        return Optional.ofNullable(BY_WIRE.get(raw.trim().toLowerCase()));
    }
}
