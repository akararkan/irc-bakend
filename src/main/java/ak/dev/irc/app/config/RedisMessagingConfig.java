package ak.dev.irc.app.config;

import ak.dev.irc.app.activity.realtime.UserActivityRealtimePublisher;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeSubscriber;
import ak.dev.irc.app.audit.realtime.AuditRealtimePublisher;
import ak.dev.irc.app.audit.realtime.AuditRealtimeSubscriber;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import ak.dev.irc.app.post.realtime.PostRealtimeSubscriber;
import ak.dev.irc.app.post.realtime.StoryRealtimePublisher;
import ak.dev.irc.app.post.realtime.StoryRealtimeSubscriber;
import ak.dev.irc.app.post.realtime.StoryTrayRealtimePublisher;
import ak.dev.irc.app.post.realtime.StoryTrayRealtimeSubscriber;
import ak.dev.irc.app.qna.realtime.QnaRealtimePublisher;
import ak.dev.irc.app.qna.realtime.QnaRealtimeSubscriber;
import ak.dev.irc.app.research.realtime.ResearchRealtimePublisher;
import ak.dev.irc.app.research.realtime.ResearchRealtimeSubscriber;
import ak.dev.irc.app.user.realtime.NotificationRedisSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisMessagingConfig {

    @Bean
    public RedisMessageListenerContainer notificationRedisListenerContainer(
            RedisConnectionFactory           connectionFactory,
            NotificationRedisSubscriber      notificationSubscriber,
            PostRealtimeSubscriber           postSubscriber,
            StoryRealtimeSubscriber          storySubscriber,
            StoryTrayRealtimeSubscriber      storyTraySubscriber,
            QnaRealtimeSubscriber            qnaSubscriber,
            ResearchRealtimeSubscriber       researchSubscriber,
            AuditRealtimeSubscriber          auditSubscriber,
            UserActivityRealtimeSubscriber   activitySubscriber) {

        RedisMessageListenerContainer container = new ResilientRedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(5000L);
        // Bounded pool for message dispatch. Without this the container uses
        // SimpleAsyncTaskExecutor, which spawns an unbounded new thread per
        // dispatch burst; with it, a slow SSE consumer can only tie up a
        // bounded number of workers while other channels keep flowing.
        container.setTaskExecutor(redisListenerExecutor());

        // Per-user notification channels.
        container.addMessageListener(notificationSubscriber,
                new PatternTopic(CHANNEL_PREFIX + "*"));

        // Per-post realtime channels (reactions, comments, replies, view counts).
        container.addMessageListener(postSubscriber,
                new PatternTopic(PostRealtimePublisher.CHANNEL_PREFIX + "*"));

        // Per-story realtime channels (views, reactions, poll votes, reply count, lifecycle).
        container.addMessageListener(storySubscriber,
                new PatternTopic(StoryRealtimePublisher.CHANNEL_PREFIX + "*"));

        // Per-viewer story-tray channels — NEW_STORY fires instantly when a followed
        // user posts; STORY_REMOVED fires on delete/expiry. Lights up the tray ring.
        container.addMessageListener(storyTraySubscriber,
                new PatternTopic(StoryTrayRealtimePublisher.CHANNEL_PREFIX + "*"));

        // Per-question realtime channels (answers, reanswers, reactions, accepts).
        container.addMessageListener(qnaSubscriber,
                new PatternTopic(QnaRealtimePublisher.CHANNEL_PREFIX + "*"));

        // Per-research realtime channels (reactions, comments, view/download/
        // share/save/citation counts, lifecycle).
        container.addMessageListener(researchSubscriber,
                new PatternTopic(ResearchRealtimePublisher.CHANNEL_PREFIX + "*"));

        // Single global audit-stream channel — admin SSE clients tap into
        // this to see every user action across every running instance.
        container.addMessageListener(auditSubscriber,
                new ChannelTopic(AuditRealtimePublisher.CHANNEL));

        // Per-user activity channels — every user's tabs/devices receive
        // their own events (search, mention lookup, reactions, comments…)
        // the moment a row is written, on any instance.
        container.addMessageListener(activitySubscriber,
                new PatternTopic(UserActivityRealtimePublisher.CHANNEL_PREFIX + "*"));

        return container;
    }

    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor redisListenerExecutor() {
        var exec = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        exec.setThreadNamePrefix("redis-sub-");
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(1000);
        // On overload the Redis dispatch thread runs the task itself —
        // natural backpressure instead of dropped realtime events.
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * Listener container that tolerates Redis being unreachable at boot.
     * Default behavior crashes the whole app on first connect failure; here we log
     * a warning and retry every 5s in the background so local dev works even when
     * `docker compose up redis` hasn't been run yet.
     */
    static class ResilientRedisMessageListenerContainer extends RedisMessageListenerContainer {

        private final AtomicBoolean retrying = new AtomicBoolean(false);
        private ScheduledExecutorService retryExec;

        @Override
        public void start() {
            try {
                super.start();
            } catch (Exception e) {
                log.warn("[REDIS] Listener container failed to start (Redis unreachable at boot?): {} — " +
                        "app will continue; retrying every 5s in background.", e.getMessage());
                scheduleRetry();
            }
        }

        private void scheduleRetry() {
            if (!retrying.compareAndSet(false, true)) return;
            retryExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-listener-boot-retry");
                t.setDaemon(true);
                return t;
            });
            retryExec.scheduleWithFixedDelay(() -> {
                if (isRunning()) {
                    shutdownRetry();
                    return;
                }
                try {
                    super.start();
                    if (isRunning()) {
                        log.info("[REDIS] Listener container started successfully after retry.");
                        shutdownRetry();
                    }
                } catch (Exception ignored) {
                    // still down — keep trying silently
                }
            }, 5, 5, TimeUnit.SECONDS);
        }

        private void shutdownRetry() {
            if (retryExec != null) {
                retryExec.shutdownNow();
                retryExec = null;
            }
            retrying.set(false);
        }

        @Override
        public void stop() {
            shutdownRetry();
            super.stop();
        }
    }
}

