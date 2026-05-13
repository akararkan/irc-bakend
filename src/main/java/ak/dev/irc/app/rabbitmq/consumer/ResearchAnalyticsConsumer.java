package ak.dev.irc.app.rabbitmq.consumer;

import ak.dev.irc.app.rabbitmq.event.research.ResearchDownloadedEvent;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchDownload;
import ak.dev.irc.app.research.entity.ResearchMedia;
import ak.dev.irc.app.research.realtime.ResearchRealtimeBroadcaster;
import ak.dev.irc.app.research.realtime.ResearchRealtimeEvent;
import ak.dev.irc.app.research.realtime.ResearchRealtimeEventType;
import ak.dev.irc.app.research.repository.ResearchDownloadRepository;
import ak.dev.irc.app.research.repository.ResearchMediaRepository;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.ANALYTICS_QUEUE;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                   Research Analytics Consumer                            │
 * │                                                                          │
 * │  Listens to: irc.queue.analytics                                        │
 * │                                                                          │
 * │  Handles:                                                                │
 * │   ResearchDownloadedEvent → save ResearchDownload + increment DL count  │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * View tracking lives in {@code ResearchServiceImpl.recordView} (Redis NX
 * dedupe + inline increment), mirroring posts and Q&A. Only download
 * analytics still flow through this queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = ANALYTICS_QUEUE, containerFactory = "rabbitListenerContainerFactory")
public class ResearchAnalyticsConsumer {

    private final ResearchRepository      researchRepo;
    private final ResearchDownloadRepository downloadRepo;
    private final ResearchMediaRepository mediaRepo;
    private final UserRepository          userRepo;
    private final ResearchRealtimeBroadcaster realtime;
    private final ak.dev.irc.app.common.cache.CounterCache counterCache;

    // Used to drop the L1 cache between a JPQL UPDATE and the re-read so the
    // broadcast carries the post-increment value, not the pre-increment entity
    // that Hibernate still holds in the persistence context.
    @PersistenceContext
    private EntityManager em;

    // ══════════════════════════════════════════════════════════════════════════
    //  Download tracking
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onResearchDownloaded(ResearchDownloadedEvent event) {
        log.debug("[ANALYTICS] ResearchDownloaded — researchId={} mediaId={} userId={}",
                event.researchId(), event.mediaId(), event.userId());

        Optional<Research> researchOpt = researchRepo.findByIdAndDeletedAtIsNull(event.researchId());
        if (researchOpt.isEmpty()) {
            log.warn("[ANALYTICS] ResearchDownloaded skipped — research not found id={}", event.researchId());
            return;
        }

        Research research = researchOpt.get();

        // Resolve optional user
        User user = null;
        if (event.userId() != null) {
            user = userRepo.findActiveById(event.userId()).orElse(null);
        }

        // Resolve optional media
        ResearchMedia media = null;
        if (event.mediaId() != null) {
            media = mediaRepo.findById(event.mediaId()).orElse(null);
            if (media != null && !media.getResearch().getId().equals(event.researchId())) {
                log.warn("[ANALYTICS] Media {} does not belong to research {}, ignoring",
                        event.mediaId(), event.researchId());
                media = null;
            }
        }

        String ip = event.ipAddress() != null ? event.ipAddress() : "unknown";

        ResearchDownload download = ResearchDownload.builder()
                .research(research)
                .media(media)
                .user(user)
                .ipAddress(ip)
                .build();

        downloadRepo.save(download);
        researchRepo.incrementDownloadCount(event.researchId());

        broadcastFreshCounters(event.researchId(), ResearchRealtimeEventType.DOWNLOAD_COUNT_UPDATED);

        log.debug("[ANALYTICS] Download saved and downloadCount incremented for researchId={}",
                event.researchId());
    }

    /**
     * Re-read the freshly-incremented counters and emit a research-channel
     * event so every connected reader sees download numbers update live
     * without reloading the page. Fail-safe — analytics writes never
     * propagate broadcast errors.
     */
    private void broadcastFreshCounters(java.util.UUID researchId, ResearchRealtimeEventType type) {
        try {
            // Flush pending writes and drop the persistence context so the
            // findById below reads the post-increment row from the DB instead
            // of returning the cached pre-increment entity loaded earlier in
            // this transaction.
            em.flush();
            em.clear();
            researchRepo.findById(researchId).ifPresent(r -> {
                java.util.Map<String, Long> counters = new java.util.HashMap<>();
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_REACTIONS, r.getReactionCount());
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_COMMENTS,  r.getCommentCount());
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_SHARES,    r.getShareCount());
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_SAVES,     r.getSaveCount());
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_VIEWS,     r.getViewCount());
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_DOWNLOADS, r.getDownloadCount());
                counters.put(ak.dev.irc.app.common.cache.CounterCache.F_CITATIONS, r.getCitationCount());
                counterCache.setAll(ak.dev.irc.app.common.cache.CounterCache.Kind.RESEARCH, researchId, counters);
                realtime.broadcast(
                    ResearchRealtimeEvent.builder()
                            .eventType(type)
                            .researchId(researchId)
                            .reactionCount(r.getReactionCount())
                            .commentCount(r.getCommentCount())
                            .shareCount(r.getShareCount())
                            .saveCount(r.getSaveCount())
                            .viewCount(r.getViewCount())
                            .downloadCount(r.getDownloadCount())
                            .citationCount(r.getCitationCount())
                            .build());
            });
        } catch (Exception ex) {
            log.debug("[ANALYTICS] broadcast skipped: {}", ex.getMessage());
        }
    }
}
