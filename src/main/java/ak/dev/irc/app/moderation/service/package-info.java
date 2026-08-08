/**
 * The moderation pipeline's service layer — the gateway every content-creating
 * surface calls, the verdict appliers, and the training/metrics orchestration.
 *
 * <p>Design reference: {@code docs/moderation/MODERATION_ROADMAP.md}.
 * Implementation notes and the admin API: {@code docs/moderation/}.</p>
 *
 * <h3>Adding a new moderated surface</h3>
 * <ol>
 *   <li>Add a constant to
 *       {@link ak.dev.irc.app.moderation.enums.ModeratedEntityType} with its
 *       inline budget, hold ceiling and fallback policy.</li>
 *   <li>Add the matching {@code hold-ms}, {@code inline-ms} and {@code fallback}
 *       entries to {@code app.moderation.*} in {@code application.yaml}.</li>
 *   <li>In the create path: mint the id, call
 *       {@link ak.dev.irc.app.moderation.service.ContentModerationService#submitOrThrow},
 *       persist with a held marker, and skip every publication side effect when
 *       {@link ak.dev.irc.app.moderation.service.ModerationOutcome#held()}.</li>
 *   <li>Gate the read paths so held content is visible only to its author.</li>
 *   <li>Write a {@link ak.dev.irc.app.moderation.service.ModerationApplier}
 *       {@code @Component} that publishes on approval and hides on rejection.
 *       It is discovered automatically — there is no registration step.</li>
 *   <li>Add a label to {@code ModerationNotifier.LABELS} so the author's
 *       notification names the right thing.</li>
 * </ol>
 *
 * <p>The one rule that matters: nothing that pushes text <em>outward</em> —
 * fan-out, search indexing, realtime broadcasts, notification bodies, inbox
 * previews — may run before a verdict. Read-path filters alone are not enough,
 * because those channels bypass them entirely.</p>
 */
package ak.dev.irc.app.moderation.service;
