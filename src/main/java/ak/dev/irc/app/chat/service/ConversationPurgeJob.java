package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.admin.ops.JobPauseRegistry;
import ak.dev.irc.app.admin.ops.JobRunRecorder;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Nightly hard-delete of conversations nobody can ever see again
 * (conversations.md §DELETE — the "when every participant has deleted it"
 * half; the storage cascade lives in {@link ConversationPurgeService}).
 *
 * <p>Two phases:</p>
 * <ol>
 *   <li><b>Retire</b> — DIRECT conversations where every member has
 *       deleted-for-me and holds no unseen messages get {@code deletedAt}
 *       stamped. From then on they behave exactly like an owner-deleted group:
 *       invisible to every read, refused by every write — except that
 *       {@code createDirect} may revive one (the pair reopening an empty
 *       thread) any time before the purge lands.</li>
 *   <li><b>Purge</b> — soft-deleted conversations whose grace has elapsed
 *       (DMs: {@code chat.purge.direct-grace-days}; owner-deleted groups and
 *       channels: {@code chat.purge.room-grace-days}, longer so moderation
 *       keeps its evidence window) are storage-swept and, only after a fully
 *       clean sweep, hard-deleted. A failed step leaves the row retired and
 *       the next night re-sweeps — idempotent by construction.</li>
 * </ol>
 *
 * <p>Deliberately throttled: one {@code chat.purge.batch} slice per category
 * per night. A backlog drains across nights instead of hammering Cassandra in
 * one sitting. Cron sits at 03:45, after the 03:30 account purge, in the same
 * maintenance band as its siblings.</p>
 *
 * <p><b>Single-scheduler assumption</b>, like every {@code @Scheduled} job in
 * this codebase (no ShedLock/leader election exists): on a multi-node deploy
 * every node fires this cron. The guarded transitions (retire / beginPurge /
 * finalize) make concurrent runs safe against data loss, but duplicate sweeps
 * waste work and can double-decrement a surviving channel's comment counters —
 * add a distributed lock to the whole job family before scaling out.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationPurgeJob {

    private final ConversationRepository conversationRepo;
    private final ConversationPurgeService purgeService;
    private final JobRunRecorder jobRunRecorder;
    private final JobPauseRegistry jobPause;

    /** Days a retired DM waits before its storage goes. Short — both sides chose
     *  this; the window exists to absorb races and give backups a cycle. */
    @Value("${chat.purge.direct-grace-days:7}")
    private int directGraceDays;

    /** Days an owner-deleted group/channel waits. Longer: admins/moderation can
     *  still reach the soft-deleted row while an abuse case is open. */
    @Value("${chat.purge.room-grace-days:30}")
    private int roomGraceDays;

    /** Conversations handled per category per night. */
    @Value("${chat.purge.batch:100}")
    private int batch;

    /** Retire passes per run — each pass refills page 0 with fresh candidates
     *  as the previous pass's rows leave the set; the cap is a runaway guard. */
    private static final int MAX_RETIRE_PASSES = 20;

    @Scheduled(cron = "0 45 3 * * *")
    public void run() {
        if (jobPause.isPaused("conversation-purge")) return;
        jobRunRecorder.record("conversation-purge", null, () -> {
            int processed = 0, failed = 0;
            LocalDateTime now = LocalDateTime.now();

            // Phase 1 — retire fully-deleted DMs.
            int retired = 0;
            for (int pass = 0; pass < MAX_RETIRE_PASSES; pass++) {
                List<UUID> eligible = conversationRepo.findFullyDeletedDirectIds(PageRequest.of(0, batch));
                int before = retired;
                for (UUID id : eligible) {
                    try {
                        if (purgeService.retireDirect(id)) retired++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("[CHAT-PURGE] retire {} failed: {}", id, e.getMessage());
                    }
                }
                if (eligible.size() < batch || retired == before) break;   // drained, or no progress
            }
            if (retired > 0) log.info("[CHAT-PURGE] retired {} fully-deleted DMs", retired);

            /* Revive pass over ALL retired DMs, graced or not: one that raced a
               send at retire time would otherwise sit invisible — delivered
               message and all — for its whole grace window. Nightly, so the
               outage is bounded by one day instead of seven. */
            int revived = 0;
            for (Conversation c : conversationRepo.findRetiredDirectsBefore(now, PageRequest.of(0, batch))) {
                try {
                    if (purgeService.reviveIfSeeable(c)) revived++;
                } catch (Exception e) {
                    log.warn("[CHAT-PURGE] revive check {} failed: {}", c.getId(), e.getMessage());
                }
            }
            if (revived > 0) log.info("[CHAT-PURGE] revived {} retired DMs that became visible", revived);

            // Phase 2 — purge graced-out retirees (one slice per category).
            int[] dm = purgeSlice(
                    conversationRepo.findRetiredDirectsBefore(now.minusDays(directGraceDays), PageRequest.of(0, batch)),
                    "DMs");
            int[] rooms = purgeSlice(
                    conversationRepo.findRetiredRoomsBefore(now.minusDays(roomGraceDays), PageRequest.of(0, batch)),
                    "rooms");
            processed += retired + revived + dm[0] + rooms[0];
            failed += dm[1] + rooms[1];
            return new JobRunRecorder.JobStats(processed, failed);
        });
    }

    /** Gate → sweep → finalize one slice; returns {processed, failed}. */
    private int[] purgeSlice(List<Conversation> slice, String label) {
        int processed = 0, failed = 0;
        for (Conversation c : slice) {
            try {
                switch (purgeService.beginPurge(c.getId())) {
                    case SKIP_GONE -> { /* another run finished it */ }
                    case SKIP_REVIVED ->
                        log.info("[CHAT-PURGE] {} is visible again — revived, not purged", c.getId());
                    case SKIP_HELD ->
                        log.info("[CHAT-PURGE] {} is under a legal hold — retained", c.getId());
                    case PROCEED -> {
                        if (purgeService.sweepStorage(c) && purgeService.finalizePurge(c.getId())) {
                            processed++;
                        } else {
                            // Left retired: the next night re-sweeps (all steps idempotent).
                            failed++;
                        }
                    }
                }
            } catch (Exception e) {
                failed++;
                log.error("[CHAT-PURGE] purge of {} failed: {}", c.getId(), e.getMessage(), e);
            }
        }
        if (processed + failed > 0) {
            log.info("[CHAT-PURGE] {}: purged {}, deferred {}", label, processed, failed);
        }
        return new int[]{processed, failed};
    }
}
