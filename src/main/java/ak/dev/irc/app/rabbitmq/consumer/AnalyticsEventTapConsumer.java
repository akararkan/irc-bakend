package ak.dev.irc.app.rabbitmq.consumer;

import ak.dev.irc.app.admin.analytics.AnalyticsEventService;
import ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The catch-all analytics tap (analytics-kpis.md §6.2 writer #1): consumes
 * the {@code #}-bound queue and turns every platform event into an
 * {@code analytics_events} row. Event type = routing key (the platform's
 * existing taxonomy); actor/target ids are best-effort-extracted from the
 * JSON envelope by well-known field names. Bodies are NEVER stored — ids and
 * envelope metadata only (privacy rule §12: no free-text content, ever).
 * This consumer never throws: an unparseable event still records its routing
 * key, and an infrastructure error drops the row rather than requeue-looping.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsEventTapConsumer {

    private static final String[] ACTOR_KEYS = {
            "actorId", "userId", "followerId", "reactorId", "sharerId", "commenterId",
            "mentionerId", "uploaderId", "authorId", "initiatorId", "hostId"};

    private static final Map<String, String> TARGET_KEYS = Map.of(
            "postId", "POST",
            "questionId", "QUESTION",
            "researchId", "RESEARCH",
            "answerId", "ANSWER",
            "soundId", "SOUND",
            "commentId", "COMMENT",
            "channelId", "CHANNEL",
            "conversationId", "CONVERSATION",
            "streamId", "STREAM");

    private final AnalyticsEventService analyticsEventService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConstants.ANALYTICS_EVENTS_QUEUE)
    public void tap(Message message) {
        try {
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            if (routingKey == null || routingKey.isBlank()) return;

            UUID actorId = null;
            String targetType = null;
            String targetId = null;
            Map<String, String> props = new LinkedHashMap<>();
            Object typeId = message.getMessageProperties().getHeader("__TypeId__");
            if (typeId != null) {
                String simple = String.valueOf(typeId);
                int dot = simple.lastIndexOf('.');
                props.put("event", dot >= 0 ? simple.substring(dot + 1) : simple);
            }

            try {
                JsonNode body = objectMapper.readTree(message.getBody());
                for (String key : ACTOR_KEYS) {
                    JsonNode node = body.get(key);
                    if (node != null && node.isTextual()) {
                        actorId = parseUuid(node.asText());
                        if (actorId != null) break;
                    }
                }
                for (Map.Entry<String, String> e : TARGET_KEYS.entrySet()) {
                    JsonNode node = body.get(e.getKey());
                    if (node != null && !node.isNull()) {
                        targetType = e.getValue();
                        targetId = node.asText();
                        break;
                    }
                }
            } catch (Exception parseEx) {
                // Non-JSON or unexpected shape — the routing key alone is still signal.
            }

            analyticsEventService.record(routingKey, actorId, targetType, targetId, props);
        } catch (Exception e) {
            log.debug("[ANALYTICS-TAP] event drop: {}", e.getMessage());
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
