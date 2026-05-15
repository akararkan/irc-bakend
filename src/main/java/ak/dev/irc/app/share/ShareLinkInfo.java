package ak.dev.irc.app.share;

/**
 * Unified payload returned by every share-link endpoint (post, research, Q&A).
 *
 * <p>All three modules return the same JSON shape so the frontend has one
 * "share" component that works for any entity:</p>
 * <ul>
 *   <li>{@code shortUrl}     — the public OG-tagged URL hosted by this backend
 *       (e.g. {@code https://api.irc/p/d6b6b64ab428}). Safe to put in a
 *       chat / tweet / WhatsApp message — opens an OG preview page that
 *       redirects to the frontend.</li>
 *   <li>{@code canonicalUrl} — the user-facing frontend URL the share
 *       eventually lands on (e.g. {@code https://app.irc/posts/{uuid}}).
 *       Use this for in-app navigation / "Open" buttons.</li>
 *   <li>{@code token}        — the bare token (post: 12-char shareLink,
 *       research: 16-char shareToken, Q&A: question UUID).</li>
 *   <li>{@code shareCount}   — fresh denormalised counter after the call.</li>
 * </ul>
 */
public record ShareLinkInfo(
        String shortUrl,
        String canonicalUrl,
        String token,
        long shareCount) {
}
