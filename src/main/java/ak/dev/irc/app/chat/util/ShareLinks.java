package ak.dev.irc.app.chat.util;

/** Builds share deep links off the configured web origin ({@code irc.base-url}). */
public final class ShareLinks {

    private ShareLinks() {}

    /** {@code base + path} with exactly one slash at the seam. */
    public static String of(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) return path;
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return b + path;
    }
}
