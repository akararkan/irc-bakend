package ak.dev.irc.app.chat.util;

import java.util.UUID;

/**
 * Deterministic key for a DIRECT conversation between two users:
 * {@code min(a,b) + ':' + max(a,b)}. Because it is identical regardless of who
 * initiates, a {@code UNIQUE(direct_key)} constraint makes "get or create DM"
 * race-safe — the database is the arbiter, no locks required.
 */
public final class DirectKeys {

    private DirectKeys() {}

    public static String of(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return (sa.compareTo(sb) <= 0) ? sa + ":" + sb : sb + ":" + sa;
    }
}
