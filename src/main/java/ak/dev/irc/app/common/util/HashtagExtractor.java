package ak.dev.irc.app.common.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls {@code #tag} hashtags out of free text — same rule set as
 * {@link MentionExtractor} but with {@code #} as the lead char.
 *
 * <p>Tags are normalised to lower case and de-duplicated; iteration order
 * matches first-occurrence in the source text so display order is stable.</p>
 */
public final class HashtagExtractor {

    private static final Pattern PATTERN = Pattern.compile(
            "(?<![\\w#])#([\\p{L}\\p{N}_]{2,50})",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private HashtagExtractor() {}

    public static Set<String> extract(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();
        Set<String> tags = new LinkedHashSet<>();
        Matcher m = PATTERN.matcher(text);
        while (m.find()) {
            tags.add(m.group(1).toLowerCase());
        }
        return tags;
    }
}
