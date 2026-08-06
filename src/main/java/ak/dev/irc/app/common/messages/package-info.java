/**
 * The centralized user-facing message registry — the code-side counterpart of
 * {@code docs/errors/user-facing-messages.md} (the human-readable catalog).
 * Every string a user can see (error messages, error codes, notification
 * copy, email verbs, inline response notes/warnings) lives here as a named
 * constant instead of an inline literal at the throw/send site.
 *
 * <h2>Conventions (follow exactly when adding a message)</h2>
 * <ul>
 *   <li>One final class per module, named {@code {Module}Messages}, with a
 *       private constructor. Constants only — no logic.</li>
 *   <li><b>Error codes</b>: {@code public static final String CODE = "CODE";}
 *       — the constant name IS the code string, so call sites can never typo
 *       a code.</li>
 *   <li><b>Message text</b>: same name with an {@code _MSG} suffix. Wording
 *       must match the catalog doc byte-for-byte.</li>
 *   <li><b>Interpolated messages</b> are templates using {@code %s} only
 *       (never {@code %d} — {@code %s} renders any type), consumed with
 *       {@code .formatted(...)}. A literal {@code %} in copy is escaped as
 *       {@code %%}.</li>
 *   <li><b>Validation copy</b> ({@code @NotBlank(message = ...)} etc.) uses
 *       {@code VAL_*} constants — plain literals, never templates, because
 *       annotation values must be compile-time constants.</li>
 *   <li><b>Notes / warnings</b> inside 200 responses use {@code NOTE_*} /
 *       {@code WARN_*}; notification titles/bodies use {@code NOTIF_*}.</li>
 *   <li>{@code ResourceNotFoundException(resource, field, value)} and
 *       {@code DuplicateResourceException(resource, field, value)} 3-arg
 *       forms are already centralized inside the exception classes — leave
 *       those call sites alone.</li>
 *   <li>Messages assembled with real logic (conditional fragments, loops)
 *       stay at the call site; only their <i>code</i> literal moves here.
 *       Don't contort dynamic copy into templates.</li>
 * </ul>
 *
 * <p>When copy changes: edit the constant here AND the corresponding row in
 * {@code docs/errors/user-facing-messages.md} — they are maintained as a
 * pair.</p>
 */
package ak.dev.irc.app.common.messages;
