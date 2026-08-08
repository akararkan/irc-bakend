package ak.dev.irc.app.moderation.service;

/**
 * One named text field of a submission — {@code title}, {@code body},
 * {@code tag[0]}, {@code alt_text[2]}, … (MODERATION_ROADMAP.md §5.4).
 *
 * <p>Scoring per field rather than per concatenated blob is what lets the review
 * queue tell a moderator <em>which</em> field tripped, and what stops a clean
 * 2,000-word article body from diluting one abusive tag below every threshold.</p>
 */
public record ModerationTextField(String name, String text) {
}
