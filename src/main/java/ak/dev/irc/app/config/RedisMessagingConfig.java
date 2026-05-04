package ak.dev.irc.app.config;

import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.post.realtime.PostRealtimeSubscriber;
import ak.dev.irc.app.qna.realtime.QnaRealtimePublisher;
import ak.dev.irc.app.qna.realtime.QnaRealtimeSubscriber;
import ak.dev.irc.app.user.realtime.NotificationRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static ak.dev.irc.app.user.realtime.NotificationRedisPublisher.CHANNEL_PREFIX;

/**
 * Configures Redis pub/sub infrastructure for real-time SSE notification delivery.
 *
 * <p>Flow:
 * <ol>
 *   <li>RabbitMQ consumer saves Notification → fires {@code NotificationPushedEvent}.</li>
 *   <li>{@code NotificationPushEventListener} (AFTER_COMMIT, @Async) calls
 *       {@code NotificationRedisPublisher} to publish to
 *       {@code irc:notifications:{userId}}.</li>
 *   <li>{@code NotificationRedisSubscriber} (wired here) receives the message on
 *       every running instance and forwards it to the local
 *       {@code NotificationSseService}, which pushes it to the SSE emitter
 *       for that user — if one is open on this instance.</li>
 * </ol>
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class RedisMessagingConfig {

    @Bean
    public RedisMessageListenerContainer notificationRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            NotificationRedisSubscriber notificationSubscriber,
            PostRealtimeSubscriber postSubscriber,
            QnaRealtimeSubscriber qnaSubscriber) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Per-user notification channels.
        container.addMessageListener(notificationSubscriber,
                new PatternTopic(CHANNEL_PREFIX + "*"));

        // Per-post realtime channels (reactions, comments, replies, view counts).
        container.addMessageListener(postSubscriber,
                new PatternTopic(PostRealtimePublisher.CHANNEL_PREFIX + "*"));

        // Per-question realtime channels (answers, reanswers, reactions, accepts).
        container.addMessageListener(qnaSubscriber,
                new PatternTopic(QnaRealtimePublisher.CHANNEL_PREFIX + "*"));

        return container;
    }
}

