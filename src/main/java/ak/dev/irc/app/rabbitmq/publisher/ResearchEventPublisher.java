package ak.dev.irc.app.rabbitmq.publisher;

import ak.dev.irc.app.rabbitmq.event.research.*;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchComment;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.*;

/**
 * Publishes research domain events to the IRC topic exchange.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void publishResearchPublished(Research research) {
        User researcher = research.getResearcher();
        ResearchPublishedEvent event = ResearchPublishedEvent.of(
                research.getId(),
                research.getTitle(),
                research.getSlug(),
                researcher.getId(),
                researcher.getUsername(),
                researcher.getFullName()
        );
        send(RESEARCH_PUBLISHED, event);
        log.debug("[EVENT] ResearchPublished published — researchId={} by researcher={}",
                research.getId(), researcher.getId());
    }

    // ── Social ────────────────────────────────────────────────────────────────

    public void publishReacted(Research research, User actor, ReactionType reactionType) {
        // Don't notify researchers about their own reactions
        if (research.getResearcher().getId().equals(actor.getId())) return;

        ResearchReactedEvent event = ResearchReactedEvent.of(
                research.getId(),
                research.getTitle(),
                research.getResearcher().getId(),
                actor.getId(),
                actor.getUsername(),
                actor.getFullName(),
                reactionType.name()
        );
        send(RESEARCH_REACTED, event);
        log.debug("[EVENT] ResearchReacted published — researchId={} actor={}",
                research.getId(), actor.getId());
    }

    public void publishCommented(Research research, User actor, ResearchComment comment) {
        // Don't notify researchers about their own comments
        if (research.getResearcher().getId().equals(actor.getId())) return;

        ResearchCommentedEvent event = ResearchCommentedEvent.of(
                research.getId(),
                research.getTitle(),
                research.getResearcher().getId(),
                actor.getId(),
                actor.getUsername(),
                actor.getFullName(),
                comment.getId(),
                comment.getContent()
        );
        send(RESEARCH_COMMENTED, event);
        log.debug("[EVENT] ResearchCommented published — researchId={} actor={}",
                research.getId(), actor.getId());
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    public void publishViewed(UUID researchId, UUID userId, String ipAddress, String userAgent) {
        ResearchViewedEvent event = ResearchViewedEvent.of(researchId, userId, ipAddress, userAgent);
        send(RESEARCH_VIEWED, event);
        log.trace("[EVENT] ResearchViewed published — researchId={}", researchId);
    }

    public void publishDownloaded(UUID researchId, UUID mediaId, UUID userId, String ipAddress) {
        ResearchDownloadedEvent event = ResearchDownloadedEvent.of(researchId, mediaId, userId, ipAddress);
        send(RESEARCH_DOWNLOADED, event);
        log.debug("[EVENT] ResearchDownloaded published — researchId={} mediaId={}", researchId, mediaId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void send(String routingKey, Object event) {
        Runnable publishAction = () -> {
            try {
                rabbitTemplate.convertAndSend(IRC_EXCHANGE, routingKey, event);
            } catch (Exception ex) {
                log.error("[EVENT] Failed to publish to routing key '{}': {}", routingKey, ex.getMessage(), ex);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAction.run();
                }
            });
            return;
        }

        publishAction.run();
    }
}
