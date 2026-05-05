package ak.dev.irc.app.rabbitmq.consumer;

import ak.dev.irc.app.rabbitmq.event.research.ResearchDownloadedEvent;
import ak.dev.irc.app.rabbitmq.event.research.ResearchViewedEvent;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchDownload;
import ak.dev.irc.app.research.entity.ResearchMedia;
import ak.dev.irc.app.research.entity.ResearchView;
import ak.dev.irc.app.research.realtime.ResearchRealtimeBroadcaster;
import ak.dev.irc.app.research.realtime.ResearchRealtimeEvent;
import ak.dev.irc.app.research.realtime.ResearchRealtimeEventType;
import ak.dev.irc.app.research.repository.ResearchDownloadRepository;
import ak.dev.irc.app.research.repository.ResearchMediaRepository;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.research.repository.ResearchViewRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
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
 * │   ResearchViewedEvent     → save ResearchView + increment viewCount     │
 * │   ResearchDownloadedEvent → save ResearchDownload + increment DL count  │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Keeping analytics processing off the HTTP request thread means the
 * API response is always fast, even if the DB is briefly under pressure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = ANALYTICS_QUEUE, containerFactory = "rabbitListenerContainerFactory")
public class ResearchAnalyticsConsumer {

    private final ResearchRepository      researchRepo;
    private final ResearchViewRepository  viewRepo;
    private final ResearchDownloadRepository downloadRepo;
    private final ResearchMediaRepository mediaRepo;
    private final UserRepository          userRepo;
    private final ResearchRealtimeBroadcaster realtime;

    // ══════════════════════════════════════════════════════════════════════════
    //  View tracking
    // ══════════════════════════════════════════════════════════════════════════

    @RabbitHandler
    @Transactional
    public void onResearchViewed(ResearchViewedEvent event) {
        log.trace("[ANALYTICS] ResearchViewed — researchId={} userId={}",
                event.researchId(), event.userId());

        Optional<Research> researchOpt = researchRepo.findByIdAndDeletedAtIsNull(event.researchId());
        if (researchOpt.isEmpty()) {
            log.warn("[ANALYTICS] ResearchViewed skipped — research not found id={}", event.researchId());
            return;
        }

        Research research = researchOpt.get();

        // Resolve optional user reference
        User user = null;
        if (event.userId() != null) {
            user = userRepo.findActiveById(event.userId()).orElse(null);
        }

        // Truncate oversized fields (defensive — service already validates, but belt-and-suspenders)
        String ip = event.ipAddress() != null
                ? (event.ipAddress().length() > 45 ? event.ipAddress().substring(0, 45) : event.ipAddress())
                : "unknown";
        String ua = event.userAgent() != null
                ? (event.userAgent().length() > 500 ? event.userAgent().substring(0, 500) : event.userAgent())
                : null;

        ResearchView view = ResearchView.builder()
                .research(research)
                .user(user)
                .ipAddress(ip)
                .userAgent(ua)
                .build();

        viewRepo.save(view);
        researchRepo.incrementViewCount(event.researchId());

        broadcastFreshCounters(event.researchId(), ResearchRealtimeEventType.VIEW_COUNT_UPDATED);

        log.debug("[ANALYTICS] View saved and viewCount incremented for researchId={}",
                event.researchId());
    }

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
     * event so every connected reader sees view / download numbers update
     * live without reloading the page. Fail-safe — analytics writes never
     * propagate broadcast errors.
     */
    private void broadcastFreshCounters(java.util.UUID researchId, ResearchRealtimeEventType type) {
        try {
            researchRepo.findById(researchId).ifPresent(r -> realtime.broadcast(
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
                            .build()));
        } catch (Exception ex) {
            log.debug("[ANALYTICS] broadcast skipped: {}", ex.getMessage());
        }
    }
}
