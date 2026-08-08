package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.search.service.ResearchSearchService;
import ak.dev.irc.app.research.service.impl.ResearchServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Finishes — or abandons — a paper whose verdict landed after
 * {@code ResearchServiceImpl.publish} had already returned
 * (MODERATION_ROADMAP.md §5.2 step 4).
 *
 * <p>A held paper is left as a DRAFT carrying a {@code moderationStatus}, so
 * approval here is a real publish: status flip, publication timestamp, and the
 * whole fan-out {@code publish()} skipped. That fan-out is reached through
 * {@link ResearchServiceImpl#publishFanout} rather than reimplemented, because
 * the last time this platform had two publish paths the second one forgot
 * Elasticsearch and Cassandra trending, and the affected papers were invisible
 * to search forever.</p>
 *
 * <p>The service is resolved through an {@link ObjectProvider}: it injects
 * {@code ContentModerationService}, which owns the applier registry, so a direct
 * constructor dependency would close a bean cycle at startup.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchModerationApplier implements ModerationApplier {

    private final ResearchRepository researchRepository;
    private final ResearchSearchService researchSearch;
    private final ak.dev.irc.app.common.tag.service.ContentTagService contentTagService;
    private final ObjectProvider<ResearchServiceImpl> researchService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.RESEARCH;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        Research research = load(moderationCase);
        if (research == null) return;
        if (!research.isHeldForModeration()) {
            // Already released (a re-drive), or never held in the first place.
            // Publishing here unconditionally would push out a plain draft the
            // author never asked to publish.
            return;
        }
        if (research.getStatus() != ResearchStatus.DRAFT) {
            // The author archived or retracted it while the verdict was in
            // flight. Clearing the hold is right; overriding their own lifecycle
            // decision to force it live is not.
            research.setModerationStatus(ModerationStatus.APPROVED);
            research.setModerationDecidedAt(LocalDateTime.now());
            researchRepository.save(research);
            return;
        }

        research.setModerationStatus(ModerationStatus.APPROVED);
        research.setModerationDecidedAt(LocalDateTime.now());
        research.setStatus(ResearchStatus.PUBLISHED);
        if (research.getPublishedAt() == null) {
            // Null on a first publish. An edit that re-entered moderation keeps
            // its original date — the paper was public before and its citation
            // record should not move.
            research.setPublishedAt(LocalDateTime.now());
        }
        researchRepository.save(research);

        UUID researcherId = research.getResearcher() == null ? null : research.getResearcher().getId();
        researchService.getObject().publishFanout(research, researcherId);
        // The feed/detail caches were populated while the paper was still held
        // (or absent). Publishing from outside the service skips the @CacheEvict
        // every owner-path transition carries.
        researchService.getObject().evictResearchCaches(research.getId());

        log.info("[MODERATION] research {} approved and published", research.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        Research research = load(moderationCase);
        if (research == null) return;
        if (research.getModerationStatus() == ModerationStatus.REJECTED) return;

        // The paper stays a DRAFT and keeps its text: the author has to be able
        // to read what was refused in order to appeal it or rewrite it.
        research.setModerationStatus(ModerationStatus.REJECTED);
        research.setModerationDecidedAt(LocalDateTime.now());
        research.setStatus(ResearchStatus.DRAFT);
        researchRepository.save(research);

        // Covers the fail-open and admin-reversal routes, where the paper did
        // reach the catalog before the verdict flipped; a no-op otherwise.
        researchSearch.deleteAsync(research.getId());
        // Same routes, same reason: archive/retract/unpublish all pair the ES
        // delete with a trending untag, and without it the tag feed keeps
        // rendering the title of a paper whose detail page now 404s.
        contentTagService.untag(research.getId());
        researchService.getObject().evictResearchCaches(research.getId());

        log.info("[MODERATION] research {} rejected and kept unpublished", research.getId());
    }

    private Research load(ModerationCase moderationCase) {
        try {
            UUID researchId = UUID.fromString(moderationCase.getEntityRef());
            Research research = researchRepository.findByIdAndDeletedAtIsNull(researchId).orElse(null);
            if (research == null) {
                // Deleted by its author while held. The case row keeps the text,
                // which is exactly why the evidence lives in
                // moderation_case_fields instead of pointing back at the paper.
                log.debug("[MODERATION] research {} gone before verdict applied", researchId);
            }
            return research;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID research ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
