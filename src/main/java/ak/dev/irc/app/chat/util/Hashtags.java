package ak.dev.irc.app.chat.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts {@code #hashtags} (and detects links) in message bodies/captions. */
public final class Hashtags {

    /** Unicode letters/digits/underscore, 1–64 chars — matches the platform's tag shape. */
    private static final Pattern TAG = Pattern.compile("#([\\p{L}\\p{N}_]{1,64})");
    private static final Pattern LINK = Pattern.compile("(?i)\\bhttps?://\\S+|\\bt\\.me/\\S+");
    private static final int MAX_TAGS = 30;

    private Hashtags() {}

    /** Lowercased, de-duplicated tags in order of first appearance; null if none. */
    public static Set<String> extract(String body) {
        if (body == null || body.indexOf('#') < 0) return null;
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TAG.matcher(body);
        while (m.find() && out.size() < MAX_TAGS) {
            out.add(m.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.isEmpty() ? null : out;
    }

    /** True when the body contains an http(s) or t.me link (the LINK gallery). */
    public static boolean hasLink(String body) {
        return body != null && LINK.matcher(body).find();
    }
}
