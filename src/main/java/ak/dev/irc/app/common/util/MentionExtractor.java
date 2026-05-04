package ak.dev.irc.app.common.util;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code @username} and the special {@code @followers} token out of free
 * text. Designed to be tolerant of punctuation and unaware of any DB state — it
 * only extracts candidate handles; resolution and dedup happen elsewhere.
 *
 * <p>Rules:
 * <ul>
 *   <li>Username characters: {@code [a-zA-Z0-9_.]} length 2–50 (matches the
 *       50-char unique column on {@code users.username}).</li>
 *   <li>Negative look-behind on {@code [\w@]} so emails like {@code foo@bar.com}
 *       and chained handles like {@code @@bob} do not produce a false positive.</li>
 *   <li>Negative look-ahead on a word-char so {@code @ann's} matches {@code ann}
 *       but {@code @bob.} matches {@code bob.} (trailing dot is allowed inside
 *       the 50-char body).</li>
 *   <li>{@code @followers} is matched case-insensitively as a special token and
 *       reported separately — it never appears in {@link #getUsernames()}.</li>
 *   <li>Returned usernames are normalised to lower-case to match the way
 *       usernames are typically stored.</li>
 * </ul>
 */
public final class MentionExtractor {

    /** Reserved token. The literal string is checked case-insensitively. */
    public static final String FOLLOWERS_TOKEN = "followers";

    /**
     * One regex matches both {@code @followers} (case-insensitive) and a normal
     * username. The special token is detected first so it cannot accidentally
     * match a real user named {@code followers}.
     */
    private static final Pattern PATTERN = Pattern.compile(
            "(?<![\\w@])@([a-zA-Z0-9_.]{2,50})",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private MentionExtractor() {}

    public static ParsedMentions extract(String text) {
        if (text == null || text.isEmpty()) {
            return ParsedMentions.EMPTY;
        }

        Set<String> usernames = new LinkedHashSet<>();
        boolean hasFollowers = false;

        Matcher m = PATTERN.matcher(text);
        while (m.find()) {
            String handle = m.group(1);
            if (FOLLOWERS_TOKEN.equalsIgnoreCase(handle)) {
                hasFollowers = true;
            } else {
                usernames.add(handle.toLowerCase());
            }
        }

        return new ParsedMentions(
                usernames.isEmpty() ? Collections.emptySet() : usernames,
                hasFollowers
        );
    }

    @Getter
    @AllArgsConstructor
    public static final class ParsedMentions {
        public static final ParsedMentions EMPTY = new ParsedMentions(Collections.emptySet(), false);

        /** Lower-cased, deduped usernames. Iteration order matches first-occurrence in the text. */
        private final Set<String> usernames;

        /** True iff the literal {@code @followers} token appeared anywhere in the text. */
        private final boolean hasFollowersToken;

        public boolean isEmpty() {
            return usernames.isEmpty() && !hasFollowersToken;
        }
    }
}
