package ak.dev.irc.app.rabbitmq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import java.util.HashMap;
import java.util.Map;

import static ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants.*;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                     IRC RabbitMQ Infrastructure                          │
 * │                                                                          │
 * │  EXCHANGE  →  QUEUE bindings:                                            │
 * │                                                                          │
 * │  irc.topic.exchange                                                      │
 * │    user.social.#        ──►  irc.queue.notifications                     │
 * │    research.lifecycle.# ──►  irc.queue.notifications                     │
 * │    research.social.#    ──►  irc.queue.notifications                     │
 * │    post.lifecycle.#     ──►  irc.queue.notifications   ← ADDED           │
 * │    post.social.#        ──►  irc.queue.notifications   ← ADDED           │
 * │    qna.lifecycle.#      ──►  irc.queue.notifications   ← ADDED           │
 * │    qna.social.#         ──►  irc.queue.notifications   ← ADDED           │
 * │    research.analytics.# ──►  irc.queue.analytics                        │
 * │                                                                          │
 * │  irc.dlx.exchange       ──►  irc.queue.dead-letter  (failed messages)   │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Configuration
public class RabbitMQConfig {

    // ── Exchanges ─────────────────────────────────────────────────────────────

    @Bean
    public TopicExchange ircExchange() {
        return ExchangeBuilder
                .topicExchange(IRC_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange ircDlxExchange() {
        return ExchangeBuilder
                .directExchange(IRC_DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    // ── Queue arguments (DLX wiring + message TTL) ────────────────────────────

    private Map<String, Object> queueArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", IRC_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", "dead-letter");
        args.put("x-message-ttl", 86_400_000); // 24h max age
        return args;
    }

    // ── Queues ────────────────────────────────────────────────────────────────

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder
                .durable(NOTIFICATION_QUEUE)
                .withArguments(queueArgs())
                .build();
    }

    @Bean
    public Queue analyticsQueue() {
        return QueueBuilder
                .durable(ANALYTICS_QUEUE)
                .withArguments(queueArgs())
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(DEAD_LETTER_QUEUE)
                .build();
    }

    // ── Bindings — notification queue ────────────────────────────────────────

    /** All user-social events (follow, unfollow, block, unblock) */
    @Bean
    public Binding notificationBindingUserSocial(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with("user.social.#");
    }

    /** Research lifecycle events (published, archived …) */
    @Bean
    public Binding notificationBindingResearchLifecycle(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with("research.lifecycle.#");
    }

    /** Research social events (reactions, comments) */
    @Bean
    public Binding notificationBindingResearchSocial(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with("research.social.#");
    }

    /**
     * Post lifecycle events (post.lifecycle.created, …).
     * FIX: was missing — PostCreatedEvent was published but never reached the queue.
     */
    @Bean
    public Binding notificationBindingPostLifecycle(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with(POST_LIFECYCLE_PATTERN);   // "post.lifecycle.#"
    }

    /**
     * Post social events (post.social.reacted, commented, comment.reacted, shared).
     * FIX: was missing — all PostReactedEvent / PostCommentedEvent etc. were silently dropped.
     */
    @Bean
    public Binding notificationBindingPostSocial(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with(POST_SOCIAL_PATTERN);      // "post.social.#"
    }

    /** Q&A lifecycle events (qna.lifecycle.created, …). */
    @Bean
    public Binding notificationBindingQnaLifecycle(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with(QNA_LIFECYCLE_PATTERN);
    }

    /** Q&A social events (qna.social.answered, …). */
    @Bean
    public Binding notificationBindingQnaSocial(Queue notificationQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(ircExchange)
                .with(QNA_SOCIAL_PATTERN);
    }

    // ── Bindings — analytics queue ────────────────────────────────────────────

    /** Research analytics events (views, downloads) */
    @Bean
    public Binding analyticsBindingResearch(Queue analyticsQueue, TopicExchange ircExchange) {
        return BindingBuilder.bind(analyticsQueue)
                .to(ircExchange)
                .with("research.analytics.#");
    }

    // ── DLX binding ───────────────────────────────────────────────────────────

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange ircDlxExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(ircDlxExchange)
                .with("dead-letter");
    }

    // ── Message converter (JSON + type header) ────────────────────────────────

    /**
     * Serialises/deserialises messages as JSON.
     * The {@code __TypeId__} header carries the full class name so that
     * Spring AMQP can dispatch to the right {@code @RabbitHandler} method.
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);

        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        // Trusted packages for incoming AMQP messages. Ensure any new event classes
        // are placed under these packages or update this list.
        typeMapper.setTrustedPackages(
                "ak.dev.irc.irc_security.rabbitmq.event.user",
                "ak.dev.irc.irc_security.rabbitmq.event.research",
                "ak.dev.irc.app.rabbitmq.event.user",
                "ak.dev.irc.app.rabbitmq.event.research",
            "ak.dev.irc.app.rabbitmq.event.post",
            "ak.dev.irc.app.rabbitmq.event.qna"
        );

        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    // ── RabbitTemplate ────────────────────────────────────────────────────────

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setDefaultReceiveQueue(NOTIFICATION_QUEUE);
        return template;
    }

    // ── Listener container factory with retry ─────────────────────────────────

    /**
     * Configures the AMQP listener container:
     *  - 3 attempts (1 original + 2 retries) with exponential back-off
     *  - After exhausting retries the message is rejected (→ DLX)
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {

        RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1_000, 2.0, 10_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(10);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(5);
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }
}