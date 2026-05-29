package ak.dev.irc.app.common.text;

/**
 * How a rich-text source string (e.g. research body, abstract) should be
 * interpreted by {@link RichTextService} when rendering to safe HTML.
 *
 * <ul>
 *   <li>{@link #PLAIN} — escape every angle bracket and convert newlines to
 *       {@code <br/>}. The safe-by-default for arbitrary text.</li>
 *   <li>{@link #MARKDOWN} — CommonMark + GFM (tables, strikethrough, autolinks)
 *       → HTML → OWASP sanitiser.</li>
 *   <li>{@link #HTML} — caller-supplied HTML, passed straight through the
 *       OWASP sanitiser. Anything not on the whitelist is stripped.</li>
 * </ul>
 *
 * Callers may pass {@code null} on input; {@link RichTextService#detectFormat}
 * picks {@code HTML} when the source looks like a tag soup, otherwise
 * {@code MARKDOWN}.
 */
public enum BodyFormat {
    PLAIN,
    MARKDOWN,
    HTML
}
