package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.moderation.PlatformKeywordService;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.moderation.ModerationProperties;
import ak.dev.irc.app.moderation.client.InferenceUnavailableException;
import ak.dev.irc.app.moderation.client.ModerationInferenceClient;
import ak.dev.irc.app.moderation.client.ScoreResult;
import ak.dev.irc.app.moderation.engine.FieldDecision;
import ak.dev.irc.app.moderation.engine.ModerationDecisionEngine;
import ak.dev.irc.app.moderation.engine.ModerationSettingsService;
import ak.dev.irc.app.moderation.engine.ModerationThresholds;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.entity.ModerationCaseField;
import ak.dev.irc.app.moderation.enums.FallbackPolicy;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationLabel;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.enums.ModerationVerdict;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import ak.dev.irc.app.moderation.worker.ModerationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The gateway every content-creating surface calls. This is the "quarantine by
 * default, publish by exception" rule of MODERATION_ROADMAP.md §3.1 made
 * concrete.
 *
 * <h3>Order of operations</h3>
 * <ol>
 *   <li><b>Blocklist first</b> (§8.2) — a hard deny-list hit rejects without ever
 *       calling the model, saving both latency and inference load. A soft flag
 *       forces at least review.</li>
 *   <li><b>Inline scoring</b> — one batched {@code /v1/score/batch} call for all
 *       fields, bounded by the entity type's inline budget (§5.3). Most clean
 *       content clears here, inside the original request, and publishes exactly
 *       as it did before this system existed.</li>
 *   <li><b>Deferred</b> — if the model is slow, borderline, or down, the case
 *       stays {@code PENDING}, a {@code moderation.requested} message goes to the
 *       queue, and the SLA sweeper backstops it (§5.6). The content service
 *       persists the unit in its held representation and skips publication side
 *       effects; the applier runs them when the verdict lands.</li>
 * </ol>
 *
 * <p>The hold duration is a <b>maximum wait, not a fixed delay</b> (§5.2). Nobody
 * waits 30 seconds for a comment that clears in 80ms.</p>
 *
 * <p>Submission runs in its own transaction ({@code REQUIRES_NEW}). Content
 * services call this from inside their own write transaction, and the case row —
 * including a rejection — must survive even when the caller then rolls back;
 * losing the record of why something was refused would make the decision
 * unexplainable, which §3.3 forbids.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentModerationService {

    /**
     * How long a deferred attempt waits before it may settle a case, giving the
     * caller time to finish writing the content row it will be applied to. Well
     * inside every hold ceiling (the shortest is 5s for live chat, which never
     * takes this path at all).
     */
    private static final int GRACE_SECONDS = 2;

    private final ModerationCaseRepository caseRepository;
    private final ModerationInferenceClient inferenceClient;
    private final ModerationDecisionEngine decisionEngine;
    private final ModerationSettingsService settings;
    private final ModerationApplierRegistry applierRegistry;
    private final ModerationNotifier notifier;
    private final ModerationRecorder moderationRecorder;
    private final PlatformKeywordService platformKeywords;
    private final ModerationEventPublisher eventPublisher;
    private final ModerationProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Own proxy, so {@link #submitOrThrow} enters {@link #submit} through Spring's
     * transaction interceptor instead of as a plain self-invocation.
     *
     * <p>This is load-bearing, not ceremony. Without it {@code submit}'s
     * {@code REQUIRES_NEW} would be silently ignored: called from a JPA service's
     * own transaction (research publish, Q&A create), the case row would join that
     * transaction, and the {@code CONTENT_REJECTED} exception {@code submitOrThrow}
     * raises would roll the caller back — taking the record of <em>why</em> the
     * content was refused with it. A decision nobody can explain is exactly what
     * §3.3 forbids.</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ContentModerationService self;

    // ── entry point for content surfaces ────────────────────────────────

    /**
     * Scores a submission and returns the verdict. Never throws for policy
     * reasons — the caller decides what a rejection means for its own HTTP
     * contract (a post refuses with 400, a live-chat line is silently dropped).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModerationOutcome submit(ModerationSubmission submission) {
        ModeratedEntityType type = submission.entityType();

        if (!settings.enabled(type)) {
            // Model scoring is off, but the deny-list is a separate, instant lever
            // (§8.2) and predates this system on several of these surfaces.
            // Turning off the classifier must not quietly turn off the blocklist.
            return blocklistOnly(submission);
        }
        if (submission.empty()) {
            // Media-only content. There is nothing for a text classifier to say
            // about it; image/video moderation is explicitly out of scope (§14).
            return ModerationOutcome.passthrough(ModerationOutcome.REASON_NO_TEXT);
        }

        ModerationCase moderationCase = openCase(submission, type);

        // 1. Deny-list pass — instant, in-process, no model call (§8.2).
        Map<String, FieldDecision> blocklist = screenBlocklist(submission);
        FieldDecision hardHit = blocklist.values().stream()
                .filter(decision -> decision.verdict() == ModerationVerdict.REJECT)
                .findFirst().orElse(null);
        if (hardHit != null) {
            settle(moderationCase, ModerationStatus.REJECTED, ModerationOutcome.REASON_BLOCKLIST,
                    "system", null, submission, blocklist);
            platformKeywords.recordHit(type.name(), submission.entityRef(),
                    submission.authorId(), hardHit.blocklistHit());
            return toOutcome(moderationCase);
        }

        // 2. Inline model pass, bounded by this entity type's latency budget.
        try {
            Map<String, FieldDecision> decisions = scoreInline(submission, type, blocklist);
            ModerationVerdict verdict = aggregate(decisions);
            settle(moderationCase, verdict.toStatus(), ModerationOutcome.REASON_MODEL,
                    "system", inferenceClient.currentModelVersion(), submission, decisions);
            recordSoftFlags(submission, blocklist);
            return toOutcome(moderationCase);

        } catch (InferenceUnavailableException unavailable) {
            // 3. No verdict. Do NOT silently approve (§7.4) — leave it PENDING,
            //    hand it to the queue, and let the sweeper apply the fallback
            //    policy if the retry never lands.
            log.debug("[MODERATION] inline scoring unavailable for {} {}: {}",
                    type, submission.entityRef(), unavailable.getMessage());
            persistFields(moderationCase, submission, blocklist);
            caseRepository.save(moderationCase);

            if (type.ephemeral()) {
                // Live chat has no queue and no row to revisit: apply the fallback
                // now or the message is lost in limbo (§6).
                ModerationStatus resolved = settings.fallback(type) == FallbackPolicy.FAIL_OPEN_SHADOW
                        ? ModerationStatus.APPROVED
                        : ModerationStatus.IN_REVIEW;
                finalizeStatusOnly(moderationCase, resolved,
                        ModerationOutcome.REASON_INFERENCE_DOWN, "system");
            } else {
                eventPublisher.publishModerationRequested(moderationCase.getId(), type,
                        submission.entityRef());
            }
            return toOutcome(moderationCase);
        }
    }

    /**
     * Convenience for surfaces that want the classic "refuse the write" contract
     * — the same shape {@code PlatformKeywordService.blockOrFlag} already gave
     * post/comment/story creates.
     *
     * <p>Deliberately NOT transactional itself, and it goes through {@link #self}:
     * the case row must be committed <em>before</em> this throws, or the rejection
     * would be rolled back along with the caller.</p>
     *
     * @throws BadRequestException {@code CONTENT_REJECTED} when the verdict blocks
     */
    public ModerationOutcome submitOrThrow(ModerationSubmission submission) {
        ModerationOutcome outcome = self.submit(submission);
        if (outcome.rejected()) {
            throw new BadRequestException(ModerationMessages.CONTENT_REJECTED_MSG,
                    ModerationMessages.CONTENT_REJECTED);
        }
        return outcome;
    }

    /**
     * For surfaces that have <b>no held representation</b> — share captions,
     * highlight titles, story-poll prose, channel admin labels, research and Q&amp;A
     * sub-resource text. These rows carry no status column and have no
     * {@link ModerationApplier}, so nothing would ever revisit a held verdict:
     * anything but a clean pass has to be refused at the door or it publishes
     * permanently unreviewed.
     *
     * <p>{@link #submitOrThrow} is wrong for them. It only refuses an outright
     * REJECT, so a borderline score — or an inference outage, which returns
     * PENDING — would sail straight through.</p>
     *
     * <p>The cost is a stricter surface: a caption the model is merely unsure
     * about is refused rather than queued. For one-line annotations that is the
     * right trade; the author edits and resubmits, and the alternative is
     * permanently unreviewable text.</p>
     *
     * @throws BadRequestException {@code CONTENT_REJECTED} on any non-approved verdict
     */
    public ModerationOutcome submitOrRefuse(ModerationSubmission submission) {
        ModerationOutcome outcome = self.submit(submission);
        if (!outcome.approved()) {
            throw new BadRequestException(ModerationMessages.CONTENT_REJECTED_MSG,
                    ModerationMessages.CONTENT_REJECTED);
        }
        return outcome;
    }

    // ── deferred paths (queue worker, SLA sweeper, admin) ───────────────

    /**
     * Re-attempts scoring for a case the inline pass could not decide. Called by
     * the queue worker; safe to call repeatedly.
     *
     * @return the case's status after the attempt
     */
    @Transactional
    public ModerationStatus rescore(UUID caseId) {
        ModerationCase moderationCase = caseRepository.findWithFields(caseId).orElse(null);
        if (moderationCase == null) {
            log.debug("[MODERATION] rescore skipped — case {} is gone", caseId);
            return null;
        }
        if (moderationCase.getStatus() != ModerationStatus.PENDING && !neverScored(moderationCase)) {
            // Already decided by another attempt, the sweeper, or an admin.
            applyIfNeeded(moderationCase);
            return moderationCase.getStatus();
        }

        // The queue message is published when submit()'s REQUIRES_NEW transaction
        // commits, which is BEFORE the caller has written the content row — a
        // Cassandra insert has no transaction to ride on at all. A worker that
        // wins that race decides the case, finds no content to flip, and
        // applyIfNeeded still stamps appliedAt: the post would stay held forever
        // with a verdict nobody ever applied. Deferring the first attempt past a
        // short grace window costs nothing (the inline path already handles the
        // fast case) and removes the race for all ten appliers at once.
        if (moderationCase.getSubmittedAt() != null
                && moderationCase.getSubmittedAt().isAfter(LocalDateTime.now().minusSeconds(GRACE_SECONDS))) {
            return ModerationStatus.PENDING;
        }

        ModeratedEntityType type = moderationCase.getEntityType();
        moderationCase.setAttempts(moderationCase.getAttempts() + 1);

        try {
            List<ModerationInferenceClient.BatchItem> items = moderationCase.getFields().stream()
                    .map(field -> new ModerationInferenceClient.BatchItem(
                            field.getId().toString(), field.getText()))
                    .toList();
            List<ScoreResult> results = inferenceClient.scoreBatch(items, settings.holdCeiling(type));
            ModerationThresholds thresholds = settings.thresholds(type);

            Map<String, ScoreResult> byId = new LinkedHashMap<>();
            results.forEach(result -> byId.put(result.fieldId(), result));

            ModerationVerdict verdict = ModerationVerdict.APPROVE;
            for (ModerationCaseField field : moderationCase.getFields()) {
                ScoreResult result = byId.get(field.getId().toString());
                FieldDecision decision = result == null
                        ? FieldDecision.approved()
                        : decisionEngine.decide(result.scores(), thresholds);
                if (field.getBlocklistHit() != null) {
                    decision = decisionEngine.merge(decision,
                            FieldDecision.softFlagged(field.getBlocklistHit()));
                }
                writeFieldDecision(field, decision);
                verdict = verdict.worstOf(decision.verdict());
            }

            moderationCase.setModelVersion(inferenceClient.currentModelVersion());
            settleCase(moderationCase, verdict.toStatus(), ModerationOutcome.REASON_MODEL, "system");
            caseRepository.save(moderationCase);
            applyIfNeeded(moderationCase);
            return moderationCase.getStatus();

        } catch (InferenceUnavailableException unavailable) {
            caseRepository.save(moderationCase);
            log.debug("[MODERATION] rescore attempt {} failed for case {}: {}",
                    moderationCase.getAttempts(), caseId, unavailable.getMessage());
            return ModerationStatus.PENDING;
        }
    }

    /**
     * The §5.6 fallback: the hold window expired with no verdict. Fail-closed
     * routes to the review queue with an SLA-breached flag; fail-open publishes
     * and flags for priority review.
     */
    @Transactional
    public void applyFallback(ModerationCase moderationCase) {
        FallbackPolicy policy = settings.fallback(moderationCase.getEntityType());
        ModerationStatus resolved = policy == FallbackPolicy.FAIL_OPEN_SHADOW
                ? ModerationStatus.APPROVED
                : ModerationStatus.IN_REVIEW;

        moderationCase.setSlaBreached(true);
        settleCase(moderationCase, resolved, ModerationOutcome.REASON_SLA_BREACH, "system");
        caseRepository.save(moderationCase);
        applyIfNeeded(moderationCase);

        log.warn("[MODERATION] SLA breach — {} {} held past deadline, policy {} → {}",
                moderationCase.getEntityType(), moderationCase.getEntityRef(), policy, resolved);
    }

    /**
     * True for a case sitting in {@code IN_REVIEW} that the classifier never
     * actually saw — it landed there because its hold window expired while the
     * inference service was unreachable, not because a score was borderline.
     *
     * <p>Re-scoring those is safe and is the point: after an outage the review
     * queue fills with content nobody has an opinion about, and asking a human to
     * hand-judge a backlog the model could settle in milliseconds is wasted work.
     * A case that reached {@code IN_REVIEW} on a real {@code MODEL} verdict is
     * excluded — the model has already spoken and a human is the escalation.</p>
     */
    private boolean neverScored(ModerationCase moderationCase) {
        return moderationCase.getStatus() == ModerationStatus.IN_REVIEW
                && ModerationOutcome.REASON_SLA_BREACH.equals(moderationCase.getReasonCode())
                && moderationCase.getModelVersion() == null;
    }

    /** An admin decided the case from the review queue (§12.1). */
    @Transactional
    public ModerationCase decideManually(UUID caseId, boolean approve, String reason, UUID adminId) {
        ModerationCase moderationCase = caseRepository.findWithFields(caseId)
                .orElseThrow(() -> new BadRequestException(
                        ModerationMessages.MODERATION_CASE_NOT_FOUND_MSG.formatted(caseId),
                        ModerationMessages.MODERATION_CASE_NOT_FOUND));

        moderationCase.setReason(reason);
        settleCase(moderationCase,
                approve ? ModerationStatus.APPROVED : ModerationStatus.REJECTED,
                ModerationOutcome.REASON_ADMIN,
                adminId == null ? "admin" : adminId.toString());
        // A manual decision supersedes an SLA breach: a human has now looked.
        moderationCase.setSlaBreached(false);

        // Clear both stamps so the applier actually re-runs. Content that cleared
        // INLINE is already marked applied — the caller published it — so without
        // this an admin reversing that approval would flip the case row, write an
        // audit entry, and change nothing about the content itself: it would stay
        // visible, stay in Elasticsearch, keep its counters, and the author would
        // never be told. A reversal that silently does nothing is worse than no
        // reversal button at all.
        moderationCase.setAppliedAt(null);
        moderationCase.setNotifiedAt(null);
        caseRepository.save(moderationCase);
        applyIfNeeded(moderationCase);
        return moderationCase;
    }

    /**
     * Drives the applier for a decided case, and records that it succeeded.
     * Idempotent: a case already applied is skipped, so the sweeper can safely
     * re-drive anything whose first attempt failed.
     */
    @Transactional
    public void applyIfNeeded(ModerationCase moderationCase) {
        if (moderationCase.getAppliedAt() != null) return;
        if (!moderationCase.getStatus().terminal()
                && moderationCase.getStatus() != ModerationStatus.IN_REVIEW) {
            return;
        }

        Optional<ModerationApplier> applier = applierRegistry.forType(moderationCase.getEntityType());
        try {
            switch (moderationCase.getStatus()) {
                case APPROVED -> applier.ifPresent(a -> a.onApproved(moderationCase));
                case REJECTED -> applier.ifPresent(a -> a.onRejected(moderationCase));
                case IN_REVIEW -> applier.ifPresent(a -> a.onInReview(moderationCase));
                default -> {
                    return;
                }
            }
            // IN_REVIEW is not "applied" — it is still waiting on a human — but the
            // author does need to be told once, and the applier's onInReview has run.
            if (moderationCase.getStatus().terminal()) {
                moderationCase.setAppliedAt(LocalDateTime.now());
            }
            moderationCase.setApplyError(null);
            // IN_REVIEW never stamps appliedAt — it is not terminal — so without a
            // separate notified marker every re-drive would send the author another
            // "your content is being reviewed" bell for the same case.
            if (moderationCase.getNotifiedAt() == null) {
                notifier.notifyAuthor(moderationCase);
                moderationCase.setNotifiedAt(LocalDateTime.now());
            }
            caseRepository.save(moderationCase);

        } catch (Exception ex) {
            // Leave appliedAt null so the sweeper retries; a verdict that never
            // reaches the content row is the worst failure mode this system has.
            moderationCase.setApplyError(truncate(ex.getMessage(), 300));
            caseRepository.save(moderationCase);
            log.error("[MODERATION] apply failed for {} {} (case {}): {}",
                    moderationCase.getEntityType(), moderationCase.getEntityRef(),
                    moderationCase.getId(), ex.getMessage());
        }
    }

    // ── read helpers used by content read paths and the admin API ───────

    /** Latest verdict for a content unit, for surfaces that gate reads on it. */
    @Transactional(readOnly = true)
    public Optional<ModerationCase> latestCase(ModeratedEntityType type, String entityRef) {
        return caseRepository.findFirstByEntityTypeAndEntityRefOrderByRevisionDesc(type, entityRef);
    }

    /** Convenience: has this unit cleared? Unknown units are treated as approved. */
    @Transactional(readOnly = true)
    public boolean isApproved(ModeratedEntityType type, String entityRef) {
        return latestCase(type, entityRef)
                .map(moderationCase -> moderationCase.getStatus() == ModerationStatus.APPROVED)
                .orElse(true);
    }

    // ── internals ───────────────────────────────────────────────────────

    /**
     * Marks every still-open case for this content as superseded by a newer
     * revision: decided, applied, and out of both the retry scan and the SLA
     * sweep. Nothing is published or hidden on their behalf — the new case owns
     * the outcome now.
     */
    private void supersedeOpenCases(ModeratedEntityType type, String entityRef, int newRevision) {
        for (ModerationCase stale :
                caseRepository.findByEntityTypeAndEntityRefOrderByRevisionDesc(type, entityRef)) {
            if (stale.getRevision() >= newRevision) continue;
            if (stale.getStatus() != ModerationStatus.PENDING) continue;
            stale.setStatus(ModerationStatus.REJECTED);
            stale.setReasonCode("SUPERSEDED");
            stale.setDecidedBy("system");
            stale.setDecidedAt(LocalDateTime.now());
            // Applied and notified are both stamped so neither the sweeper's
            // unapplied scan nor the notifier ever touches it: the author is being
            // told about the NEW revision, not this abandoned one.
            stale.setAppliedAt(LocalDateTime.now());
            stale.setNotifiedAt(LocalDateTime.now());
            caseRepository.save(stale);
        }
    }

    /**
     * Degraded mode: enforce the deny-list only, exactly as the four Cassandra
     * create paths did before this system existed — a hard hit rejects, a soft
     * flag publishes and drops a queue hit. No case row is written, because
     * without the model there is no verdict to explain or revisit.
     */
    private ModerationOutcome blocklistOnly(ModerationSubmission submission) {
        Map<String, FieldDecision> screened = screenBlocklist(submission);
        for (FieldDecision decision : screened.values()) {
            if (decision.verdict() == ModerationVerdict.REJECT) {
                platformKeywords.recordHit(submission.entityType().name(), submission.entityRef(),
                        submission.authorId(), decision.blocklistHit());
                return new ModerationOutcome(ModerationStatus.REJECTED, null, null, 0,
                        decision.blocklistHit(), ModerationOutcome.REASON_BLOCKLIST, null);
            }
        }
        recordSoftFlags(submission, screened);
        return ModerationOutcome.passthrough(ModerationOutcome.REASON_DISABLED);
    }

    private ModerationCase openCase(ModerationSubmission submission, ModeratedEntityType type) {
        LocalDateTime now = LocalDateTime.now();
        int revision = caseRepository
                .findFirstByEntityTypeAndEntityRefOrderByRevisionDesc(type, submission.entityRef())
                .map(previous -> previous.getRevision() + 1)
                .orElse(1);

        // Retire any still-open case for the same content. An edit re-submits the
        // unit, and the older case is holding the PREVIOUS text — leave it PENDING
        // and the worker or the sweeper will eventually settle it and hand its
        // stale verdict to the applier, publishing or burying the row on the
        // strength of wording that no longer exists.
        if (revision > 1) {
            supersedeOpenCases(type, submission.entityRef(), revision);
        }

        return ModerationCase.builder()
                .entityType(type)
                .entityRef(submission.entityRef())
                .parentRef(submission.parentRef())
                .authorId(submission.authorId())
                .status(ModerationStatus.PENDING)
                .submittedAt(now)
                .holdDeadline(now.plus(settings.holdCeiling(type)))
                .revision(revision)
                .attempts(0)
                .build();
    }

    /**
     * Runs the deny-list over each field independently.
     * {@code PlatformKeywordService.blockOrFlag} throws on a hard hit and returns
     * the term on a soft flag, so both outcomes are translated into a
     * {@link FieldDecision} here rather than propagating its exception — this
     * pipeline records the rejection before refusing.
     */
    private Map<String, FieldDecision> screenBlocklist(ModerationSubmission submission) {
        Map<String, FieldDecision> byField = new LinkedHashMap<>();
        for (ModerationTextField field : submission.textFields()) {
            try {
                String flagged = platformKeywords.blockOrFlag(field.text());
                if (flagged != null) {
                    byField.put(field.name(), FieldDecision.softFlagged(flagged));
                }
            } catch (BadRequestException hardBlock) {
                byField.put(field.name(), FieldDecision.hardBlocked(firstBlockTerm(field.text())));
            } catch (Exception infra) {
                // blockOrFlag is itself fail-open on infra errors; mirror that here
                // rather than blocking content on a Redis blip.
                log.debug("[MODERATION] blocklist screen skipped for field {}: {}",
                        field.name(), infra.getMessage());
            }
        }
        return byField;
    }

    private String firstBlockTerm(String text) {
        try {
            List<String> matches = platformKeywords.test(text);
            return matches.isEmpty() ? "blocked-term" : truncate(matches.get(0), 100);
        } catch (Exception ignored) {
            return "blocked-term";
        }
    }

    private Map<String, FieldDecision> scoreInline(ModerationSubmission submission,
                                                   ModeratedEntityType type,
                                                   Map<String, FieldDecision> blocklist) {
        List<ModerationTextField> fields = submission.textFields();
        List<ModerationInferenceClient.BatchItem> items = new ArrayList<>(fields.size());
        for (ModerationTextField field : fields) {
            items.add(new ModerationInferenceClient.BatchItem(field.name(), field.text()));
        }

        Duration budget = settings.inlineBudget(type);
        List<ScoreResult> results = inferenceClient.scoreBatch(items, budget);
        ModerationThresholds thresholds = settings.thresholds(type);

        Map<String, ScoreResult> byName = new LinkedHashMap<>();
        results.forEach(result -> byName.put(result.fieldId(), result));

        Map<String, FieldDecision> decisions = new LinkedHashMap<>();
        for (ModerationTextField field : fields) {
            ScoreResult result = byName.get(field.name());
            FieldDecision decision = result == null
                    ? FieldDecision.approved()
                    : decisionEngine.decide(result.scores(), thresholds);
            decisions.put(field.name(), decisionEngine.merge(decision, blocklist.get(field.name())));
        }
        return decisions;
    }

    private ModerationVerdict aggregate(Map<String, FieldDecision> decisions) {
        ModerationVerdict verdict = ModerationVerdict.APPROVE;
        for (FieldDecision decision : decisions.values()) {
            verdict = verdict.worstOf(decision.verdict());
        }
        return verdict;
    }

    /**
     * Terminal write for the inline path: persist every field with its text and
     * decision, stamp the verdict, log it, and notify the author when the answer
     * is anything other than "published".
     */
    private void settle(ModerationCase moderationCase, ModerationStatus status, String reasonCode,
                        String decidedBy, String modelVersion,
                        ModerationSubmission submission, Map<String, FieldDecision> decisions) {
        persistFieldDecisions(moderationCase, submission, decisions);
        moderationCase.setModelVersion(modelVersion);
        settleCase(moderationCase, status, reasonCode, decidedBy);

        // The inline path publishes through the caller, not the applier — the
        // content row does not exist yet, so there is nothing left for an applier
        // to flip and the case is already fully applied.
        if (status.terminal()) {
            moderationCase.setAppliedAt(LocalDateTime.now());
        }
        caseRepository.save(moderationCase);

        if (status != ModerationStatus.APPROVED) {
            notifier.notifyAuthor(moderationCase);
            moderationCase.setNotifiedAt(LocalDateTime.now());
            caseRepository.save(moderationCase);
        }
    }

    private void finalizeStatusOnly(ModerationCase moderationCase, ModerationStatus status,
                                    String reasonCode, String decidedBy) {
        settleCase(moderationCase, status, reasonCode, decidedBy);
        moderationCase.setAppliedAt(status.terminal() ? LocalDateTime.now() : null);
        caseRepository.save(moderationCase);
    }

    /** Stamps the verdict fields shared by every decision path. */
    private void settleCase(ModerationCase moderationCase, ModerationStatus status,
                            String reasonCode, String decidedBy) {
        moderationCase.setStatus(status);
        moderationCase.setReasonCode(reasonCode);
        moderationCase.setDecidedBy(decidedBy);
        moderationCase.setDecidedAt(LocalDateTime.now());

        // Roll the worst field up to the case so the queue can sort by risk
        // without joining the fields table.
        String topLabel = null;
        double topScore = 0;
        String blocklistHit = null;
        for (ModerationCaseField field : moderationCase.getFields()) {
            if (field.getMaxScore() != null && field.getMaxScore() > topScore) {
                topScore = field.getMaxScore();
                topLabel = field.getMaxLabel();
            }
            if (blocklistHit == null) blocklistHit = field.getBlocklistHit();
        }
        moderationCase.setMaxLabel(topLabel);
        moderationCase.setMaxScore(topScore);
        moderationCase.setBlocklistHit(blocklistHit);

        recordDecision(moderationCase);
    }

    /**
     * Writes the durable, human-readable decision row. Reuses the existing
     * {@code moderation_decisions} table so automated verdicts sit in the same
     * audit trail as admin takedowns — the roadmap's §9 {@code moderation_audit_log}
     * with no second, parallel log to reconcile.
     */
    private void recordDecision(ModerationCase moderationCase) {
        try {
            String action = switch (moderationCase.getStatus()) {
                case APPROVED -> "AUTO_APPROVED";
                case REJECTED -> "AUTO_REJECTED";
                case IN_REVIEW -> "SENT_TO_REVIEW";
                case PENDING -> "HELD";
            };
            if (ModerationOutcome.REASON_ADMIN.equals(moderationCase.getReasonCode())) {
                action = moderationCase.getStatus() == ModerationStatus.APPROVED
                        ? "ADMIN_MODERATION_APPROVE" : "ADMIN_MODERATION_REJECT";
            }
            String metadata = "%s label=%s score=%.4f model=%s%s".formatted(
                    moderationCase.getReasonCode(),
                    moderationCase.getMaxLabel(),
                    moderationCase.getMaxScore() == null ? 0 : moderationCase.getMaxScore(),
                    moderationCase.getModelVersion(),
                    moderationCase.isSlaBreached() ? " sla=breached" : "");
            moderationRecorder.decision(moderationCase.getEntityType().name(),
                    moderationCase.getEntityRef(), action,
                    moderationCase.getReason(), null, metadata);
        } catch (Exception ex) {
            log.warn("[MODERATION] decision log write failed for case {}: {}",
                    moderationCase.getId(), ex.getMessage());
        }
    }

    private void persistFields(ModerationCase moderationCase, ModerationSubmission submission,
                               Map<String, FieldDecision> blocklist) {
        if (!moderationCase.getFields().isEmpty()) return;
        for (ModerationTextField field : submission.textFields()) {
            FieldDecision decision = blocklist.get(field.name());
            moderationCase.addField(ModerationCaseField.builder()
                    .fieldName(truncate(field.name(), 60))
                    .text(field.text())
                    .verdict(decision == null ? null : decision.verdict())
                    .blocklistHit(decision == null ? null : decision.blocklistHit())
                    .maxScore(0.0)
                    .build());
        }
    }

    /**
     * Stores each submitted field alongside what the engine said about it. The
     * text is kept verbatim: the review queue has to render what was actually
     * written even after the content row is hard-deleted or its Cassandra TTL
     * expires, which is exactly when a moderator most needs to see it.
     */
    private void persistFieldDecisions(ModerationCase moderationCase,
                                       ModerationSubmission submission,
                                       Map<String, FieldDecision> decisions) {
        if (!moderationCase.getFields().isEmpty()) return;
        for (ModerationTextField submitted : submission.textFields()) {
            ModerationCaseField field = ModerationCaseField.builder()
                    .fieldName(truncate(submitted.name(), 60))
                    .text(submitted.text())
                    .maxScore(0.0)
                    .build();
            moderationCase.addField(field);
            FieldDecision decision = decisions.get(submitted.name());
            if (decision != null) {
                writeFieldDecision(field, decision);
            }
        }
    }

    private void writeFieldDecision(ModerationCaseField field, FieldDecision decision) {
        field.setVerdict(decision.verdict());
        field.setMaxLabel(decision.topLabel() == null ? null : decision.topLabel().wire());
        field.setMaxScore(decision.topScore());
        if (decision.blocklistHit() != null) {
            field.setBlocklistHit(truncate(decision.blocklistHit(), 100));
        }
        field.setScoresJson(toJson(decision.scores()));
    }

    private void recordSoftFlags(ModerationSubmission submission, Map<String, FieldDecision> blocklist) {
        blocklist.values().stream()
                .filter(decision -> decision.blocklistHit() != null)
                .findFirst()
                .ifPresent(decision -> platformKeywords.recordHit(
                        submission.entityType().name(), submission.entityRef(),
                        submission.authorId(), decision.blocklistHit()));
    }

    private ModerationOutcome toOutcome(ModerationCase moderationCase) {
        return new ModerationOutcome(
                moderationCase.getStatus(),
                moderationCase.getId(),
                moderationCase.getMaxLabel(),
                moderationCase.getMaxScore() == null ? 0 : moderationCase.getMaxScore(),
                moderationCase.getBlocklistHit(),
                moderationCase.getReasonCode(),
                moderationCase.getHoldDeadline());
    }

    private String toJson(Map<ModerationLabel, Double> scores) {
        if (scores == null || scores.isEmpty()) return null;
        Map<String, Double> wire = new LinkedHashMap<>();
        scores.forEach((label, value) -> wire.put(label.wire(), value));
        try {
            return objectMapper.writeValueAsString(wire);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Exposed for the ops board: how many attempts the worker makes before giving up. */
    public int maxWorkerAttempts() {
        return Math.max(2, properties.getInference().getMaxAttempts() + 2);
    }
}
