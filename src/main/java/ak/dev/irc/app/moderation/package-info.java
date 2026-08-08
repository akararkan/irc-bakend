/**
 * Automated text moderation — quarantine-then-publish for every surface that
 * carries user-written text.
 *
 * <p>Design: {@code docs/moderation/MODERATION_ROADMAP.md}, cited from this code
 * as {@code §n}. Implementation map, deviations and known edges:
 * {@code docs/moderation/architecture.md}.</p>
 *
 * <h3>The shape</h3>
 * <pre>
 *   create path → ContentModerationService.submit()
 *                   ├─ blocklist (in-process, instant)          §8.2
 *                   ├─ model     (HTTP → model-inference:8000)  §7
 *                   └─ Decision Engine (bands, per entity type) §8.1
 *                        ├─ APPROVE → caller publishes as it always did
 *                        ├─ REJECT  → nothing persisted, CONTENT_REJECTED
 *                        └─ HELD    → written, not published; applier finishes it
 *
 *   deferred → irc.queue.moderation → ModerationWorker
 *            → ModerationSlaSweeper (every 5s, DB-only, the safety net)  §5.6
 *            → admin review queue                                        §12.1
 * </pre>
 *
 * <h3>Three invariants</h3>
 * <ol>
 *   <li><b>Quarantine by default.</b> A unit becomes visible to anyone other than
 *       its author only after an explicit APPROVED verdict, or after a configured
 *       fallback policy lets it through on a documented SLA breach.</li>
 *   <li><b>"The model didn't answer" is never "the model said clean."</b>
 *       {@code InferenceUnavailableException} is a distinct outcome with its own
 *       policy; nothing in this package converts a failure into an approval.</li>
 *   <li><b>Every decision is explainable and reversible.</b> Raw per-label scores,
 *       the thresholds in force and the model version are stored per field, and
 *       every verdict writes a row to the shared {@code moderation_decisions}
 *       audit trail.</li>
 * </ol>
 *
 * <p>Policy lives here, in Java, backed by an admin-editable settings table. The
 * Python container is a pure scorer that has no opinion about what counts as too
 * toxic — which is what lets sensitivity change without redeploying it.</p>
 */
package ak.dev.irc.app.moderation;
