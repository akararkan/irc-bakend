package ak.dev.irc.app.admin.logs;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Parsed form of the Log-Explorer filter grammar (logs-audit.md §4.1):
 *
 * <pre>user:&lt;uuid|@username&gt; ip:&lt;addr&gt; outcome:&lt;...&gt; store:&lt;audit|login|settings|consent|reports|dlq&gt;
 * since:&lt;iso|relative 2h/7d&gt; until:&lt;iso&gt; text:"substring"</pre>
 *
 * Discrete request params merge over the grammar string (params win).
 * Unknown tokens are ignored rather than rejected — the grammar is a filter
 * language, not a validator.
 */
public record ExploreQuery(UUID userId,
                           String usernameAnchor,
                           String ip,
                           String outcome,
                           LocalDateTime since,
                           LocalDateTime until,
                           String text,
                           Set<String> stores) {

    private static final Set<String> KNOWN_STORES =
            Set.of("audit", "login", "settings", "consent", "reports", "dlq");

    public static ExploreQuery of(String q, String store, UUID userId, String ip, String outcome,
                                  LocalDateTime since, LocalDateTime until, String text) {
        UUID parsedUser = null;
        String usernameAnchor = null;
        String parsedIp = null;
        String parsedOutcome = null;
        LocalDateTime parsedSince = null;
        LocalDateTime parsedUntil = null;
        String parsedText = null;
        Set<String> stores = new LinkedHashSet<>();

        if (q != null && !q.isBlank()) {
            for (String token : tokenize(q)) {
                int colon = token.indexOf(':');
                if (colon <= 0) continue;
                String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = stripQuotes(token.substring(colon + 1));
                switch (key) {
                    case "user" -> {
                        if (value.startsWith("@")) {
                            usernameAnchor = value.substring(1);
                        } else {
                            try {
                                parsedUser = UUID.fromString(value);
                            } catch (IllegalArgumentException ignored) {
                                usernameAnchor = value;
                            }
                        }
                    }
                    case "ip" -> parsedIp = value;
                    case "outcome" -> parsedOutcome = value.toUpperCase(Locale.ROOT);
                    case "store" -> Arrays.stream(value.split(","))
                            .map(s -> s.trim().toLowerCase(Locale.ROOT))
                            .filter(KNOWN_STORES::contains)
                            .forEach(stores::add);
                    case "since" -> parsedSince = parseTime(value);
                    case "until" -> parsedUntil = parseTime(value);
                    case "text" -> parsedText = value;
                    default -> {
                        // op:/status:/path:/resource: tokens are accepted but only
                        // the audit adapter could serve them — ignored quietly.
                    }
                }
            }
        }

        if (store != null && !store.isBlank()) {
            stores.clear();
            Arrays.stream(store.split(","))
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .filter(KNOWN_STORES::contains)
                    .forEach(stores::add);
        }
        return new ExploreQuery(
                userId != null ? userId : parsedUser,
                userId != null ? null : usernameAnchor,
                ip != null && !ip.isBlank() ? ip.trim() : parsedIp,
                outcome != null && !outcome.isBlank() ? outcome.trim().toUpperCase(Locale.ROOT) : parsedOutcome,
                since != null ? since : parsedSince,
                until != null ? until : parsedUntil,
                text != null && !text.isBlank() ? text.trim() : parsedText,
                stores);
    }

    public ExploreQuery withUserId(UUID resolved) {
        return new ExploreQuery(resolved, null, ip, outcome, since, until, text, stores);
    }

    public boolean wantsStore(String store) {
        return stores.isEmpty() || stores.contains(store);
    }

    public boolean matchesTime(LocalDateTime at) {
        if (at == null) return true;
        if (since != null && at.isBefore(since)) return false;
        return until == null || !at.isAfter(until);
    }

    public boolean matchesText(String haystack) {
        if (text == null || text.isBlank()) return true;
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (userId != null) sb.append("user:").append(userId).append(' ');
        if (ip != null) sb.append("ip:").append(ip).append(' ');
        if (outcome != null) sb.append("outcome:").append(outcome).append(' ');
        if (since != null) sb.append("since:").append(since).append(' ');
        if (until != null) sb.append("until:").append(until).append(' ');
        if (text != null) sb.append("text:\"").append(text).append("\" ");
        sb.append("stores:").append(stores.isEmpty() ? "all-eligible" : stores);
        return sb.toString().trim();
    }

    /** Splits on spaces but keeps quoted values (`text:"two words"`) intact. */
    private static String[] tokenize(String q) {
        return q.trim().split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
    }

    private static String stripQuotes(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** ISO date-time or relative shorthand: {@code 15m}, {@code 2h}, {@code 7d}. */
    private static LocalDateTime parseTime(String value) {
        try {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.matches("\\d+m")) {
                return LocalDateTime.now().minusMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (v.matches("\\d+h")) {
                return LocalDateTime.now().minusHours(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (v.matches("\\d+d")) {
                return LocalDateTime.now().minusDays(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            return LocalDateTime.parse(value.trim());
        } catch (Exception e) {
            return null;   // unparseable time token → no bound (filter, not validator)
        }
    }
}
