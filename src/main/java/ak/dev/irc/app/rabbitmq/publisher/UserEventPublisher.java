package ak.dev.irc.app.rabbitmq.publisher;

import ak.dev.irc.app.rabbitmq.event.user.*;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.*;

/**
 * Publishes user-social domain events to the IRC topic exchange.
 *
 * All methods are fire-and-forget: if the broker is temporarily unavailable
 * Spring Retry (configured in RabbitMQConfig) will attempt 3 times before
 * logging the failure and continuing — ensuring the HTTP response is never blocked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    // ── Follow ────────────────────────────────────────────────────────────────

    public void publishFollowed(User actor, User target) {
        UserFollowedEvent event = UserFollowedEvent.of(
                actor.getId(), actor.getUsername(), actor.getFullName(), target.getId()
        );
        send(USER_FOLLOWED, event);
        log.debug("[EVENT] UserFollowed published — actor={} ({}) → target={}",
                actor.getId(), actor.getUsername(), target.getId());
    }

    public void publishUnfollowed(UUID actorId, UUID targetId) {
        UserUnfollowedEvent event = UserUnfollowedEvent.of(actorId, targetId);
        send(USER_UNFOLLOWED, event);
        log.debug("[EVENT] UserUnfollowed published — actor={} → target={}", actorId, targetId);
    }

    // ── Block ─────────────────────────────────────────────────────────────────

    public void publishBlocked(UUID actorId, UUID targetId) {
        UserBlockedEvent event = UserBlockedEvent.of(actorId, targetId);
        send(USER_BLOCKED, event);
        log.debug("[EVENT] UserBlocked published — actor={} → target={}", actorId, targetId);
    }

    public void publishUnblocked(User actor, User target) {
        UserUnblockedEvent event = UserUnblockedEvent.of(
                actor.getId(), actor.getUsername(), actor.getFullName(), target.getId()
        );
        send(USER_UNBLOCKED, event);
        log.debug("[EVENT] UserUnblocked published — actor={} ({}) → target={}",
                actor.getId(), actor.getUsername(), target.getId());
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void send(String routingKey, Object event) {
        try {
            rabbitTemplate.convertAndSend(IRC_EXCHANGE, routingKey, event);
        } catch (Exception ex) {
            // Never let a publishing failure break the business operation
            log.error("[EVENT] Failed to publish to routing key '{}': {}", routingKey, ex.getMessage(), ex);
        }
    }
}
