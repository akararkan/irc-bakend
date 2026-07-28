package ak.dev.irc.app.chat.enums;

/**
 * Ephemeral "tap" reactions that float up over a live stream — the burst of hearts
 * you tap during a TikTok / Instagram live. These are <b>not</b> the platform's
 * single persisted {@code LIKE} reaction (posts / research / Q&amp;A keep exactly one
 * of those): a live tap is never stored, never counted into an entity — it is a
 * transient animation broadcast to whoever is watching right now and then gone.
 * That is why a small expressive set is fine here where the rest of the app is
 * deliberately single-reaction.
 *
 * <p>{@code LIKE} is the default when a client sends a tap without naming one.</p>
 */
public enum StreamReactionType { LIKE, LOVE, CLAP, FIRE, WOW }
