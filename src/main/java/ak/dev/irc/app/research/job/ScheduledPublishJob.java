package ak.dev.irc.app.research.job;

import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.service.ResearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Auto-publishes DRAFT research whose {@code scheduledPublishAt} has arrived,
 * making the field actually do something (E1).
 *
 * <p>Runs every {@code app.research.scheduled-publish-ms} (default 60 s). For
 * each due draft it calls the normal {@link ResearchService#publish} path — so
 * the full fan-out (Elasticsearch index, @mentions, Cassandra trending tags, the
 * {@code RESEARCH_PUBLISHED} lifecycle broadcast) happens exactly as for a manual
 * publish. The actor is the research's own owner, so the owner check passes.</p>
 *
 * <p>Each item is published in its own transaction and isolated in try/catch, so
 * one incomplete draft (e.g. missing abstract → validation error) doesn't block
 * the rest. Once published the row leaves the {@code status = DRAFT} filter, so it
 * is never re-published.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPublishJob {

    private final ResearchRepository researchRepo;
    private final ResearchService    researchService;
    private final ak.dev.irc.app.admin.ops.JobPauseRegistry jobPause;

    @Scheduled(initialDelayString = "${app.research.scheduled-publish-initial-ms:30000}",
               fixedDelayString   = "${app.research.scheduled-publish-ms:60000}")
    public void publishDueResearch() {
        if (jobPause.isPaused("research-scheduled-publish")) return;
        List<Research> due;
        try {
            due = researchRepo.findDueForScheduledPublish(ResearchStatus.DRAFT, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[SCHED-PUBLISH] scan for due drafts failed: {}", e.getMessage());
            return;
        }
        if (due.isEmpty()) return;

        log.info("[SCHED-PUBLISH] {} draft(s) due for auto-publish", due.size());
        for (Research r : due) {
            try {
                researchService.publish(r.getId(), r.getResearcher().getId());
                log.info("[SCHED-PUBLISH] auto-published {} (scheduled {})",
                        r.getId(), r.getScheduledPublishAt());
            } catch (Exception e) {
                // Incomplete draft, race, etc. — skip this one, keep going.
                log.warn("[SCHED-PUBLISH] could not auto-publish {}: {}", r.getId(), e.getMessage());
            }
        }
    }
}
