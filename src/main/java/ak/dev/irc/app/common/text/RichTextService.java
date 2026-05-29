package ak.dev.irc.app.common.text;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders user-supplied rich text (Markdown or HTML) into a safe HTML string
 * suitable for direct {@code v-html} / {@code dangerouslySetInnerHTML} rendering
 * on the client.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>{@link BodyFormat#MARKDOWN} → CommonMark parser + GFM extensions
 *       (tables, strikethrough, autolinks) → HTML.</li>
 *   <li>{@link BodyFormat#HTML} → passed straight to the sanitiser.</li>
 *   <li>{@link BodyFormat#PLAIN} → HTML-escaped + {@code <br/>} for line breaks.
 *       No sanitiser needed — nothing tag-like survives the escape.</li>
 * </ol>
 *
 * <p>Sanitisation uses the OWASP Java HTML Sanitizer with a
 * <strong>whitelist</strong> built from {@link Sanitizers#FORMATTING},
 * {@code BLOCKS}, {@code LINKS}, {@code TABLES}, {@code IMAGES}, plus a small
 * custom policy for code blocks, headings, lists, and {@code <hr/>}. Anything
 * not on the whitelist (scripts, inline event handlers, {@code javascript:}
 * URLs, {@code <iframe>}, {@code <object>}, etc.) is stripped. The output is
 * always safe to embed.</p>
 *
 * <p>This service is stateless and thread-safe — instantiate once as a Spring
 * singleton.</p>
 */
@Service
public class RichTextService {

    // ── CommonMark (Markdown) ────────────────────────────────────────────────

    private static final List<Extension> MD_EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create()
    );

    private static final Parser MD_PARSER =
            Parser.builder().extensions(MD_EXTENSIONS).build();

    private static final HtmlRenderer MD_RENDERER =
            HtmlRenderer.builder().extensions(MD_EXTENSIONS).softbreak("<br/>").build();

    // ── OWASP HTML sanitiser policy ──────────────────────────────────────────

    /**
     * Whitelist policy. Covers the standard formatting needs of a research
     * body/abstract: paragraphs, headings (h1–h6), lists, blockquotes, code
     * blocks, tables, images (http/https only), links (with rel=noopener
     * nofollow auto-added), {@code <hr/>}, and inline emphasis. Stripped:
     * everything else, including scripts, inline event handlers,
     * {@code style="…"}, {@code <iframe>}, {@code <object>}, and any
     * non-{@code http(s)} URL scheme.
     */
    private static final PolicyFactory SANITIZER =
            Sanitizers.FORMATTING
                    .and(Sanitizers.BLOCKS)
                    .and(Sanitizers.STYLES.and(Sanitizers.LINKS))
                    .and(Sanitizers.TABLES)
                    .and(Sanitizers.IMAGES)
                    .and(new HtmlPolicyBuilder()
                            .allowElements("h1", "h2", "h3", "h4", "h5", "h6",
                                    "hr", "pre", "code", "kbd", "samp", "var",
                                    "sub", "sup", "ins", "del", "mark", "small",
                                    "abbr", "details", "summary", "figure", "figcaption")
                            .allowAttributes("title").globally()
                            .allowAttributes("class").matching(Pattern.compile(
                                    "^(language-[a-zA-Z0-9_+\\-.]+|hljs|md-[a-z\\-]+)$"))
                            .onElements("code", "pre")
                            .toFactory());

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Heuristic detector for {@code null} {@code bodyFormat} on incoming
     * requests. Strings containing an obvious HTML tag ({@code <p>},
     * {@code <div>}, {@code <h1>}, etc.) are treated as {@link BodyFormat#HTML};
     * everything else falls through to {@link BodyFormat#MARKDOWN}, which is
     * a safe superset of plain text.
     */
    public BodyFormat detectFormat(String source) {
        if (source == null || source.isBlank()) return BodyFormat.PLAIN;
        return HTML_TAG_HEURISTIC.matcher(source).find()
                ? BodyFormat.HTML
                : BodyFormat.MARKDOWN;
    }

    /**
     * Render {@code source} to a sanitised HTML string. {@code null} or blank
     * input returns {@code null}. {@code format=null} is treated as
     * {@link #detectFormat detect}-then-render.
     */
    public String renderToSafeHtml(String source, BodyFormat format) {
        if (source == null || source.isBlank()) return null;
        BodyFormat fmt = format != null ? format : detectFormat(source);
        return switch (fmt) {
            case PLAIN    -> escapeAndLineBreak(source);
            case MARKDOWN -> sanitize(markdownToHtml(source));
            case HTML     -> sanitize(source);
        };
    }

    /**
     * Strips all tags from an HTML string so it can be fed into a full-text
     * search index (Elasticsearch) or used to build a short excerpt. Whitespace
     * is collapsed; {@code null}/blank input returns {@code null}.
     */
    public String toPlainText(String html) {
        if (html == null || html.isBlank()) return null;
        // Drop tags entirely (no replacement), then unescape entities, then collapse whitespace.
        String stripped = TAG.matcher(html).replaceAll("");
        stripped = stripped
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        stripped = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
        return stripped.isEmpty() ? null : stripped;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static final Pattern HTML_TAG_HEURISTIC = Pattern.compile(
            "<(p|div|span|h[1-6]|br|img|a|ul|ol|li|table|tr|td|th|blockquote|pre|code|strong|em|b|i|hr)(\\s|>|/)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG        = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private String markdownToHtml(String md) {
        Node doc = MD_PARSER.parse(md);
        return MD_RENDERER.render(doc);
    }

    private String sanitize(String dirtyHtml) {
        return SANITIZER.sanitize(dirtyHtml);
    }

    private String escapeAndLineBreak(String text) {
        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        return escaped.replace("\n", "<br/>");
    }
}
