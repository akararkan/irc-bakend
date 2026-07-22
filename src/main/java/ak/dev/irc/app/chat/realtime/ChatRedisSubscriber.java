package ak.dev.irc.app.chat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Subscribes to {@code irc:chat:*} on every instance and forwards each event to
 * the local {@link ChatSseService}, which writes it to the user's SSE emitter(s)
 * if any are held here. Registered in {@code RedisMessagingConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRedisSubscriber implements MessageListener {

    private final ChatSseService sseService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String userIdStr = channel.replace(ChatRedisPublisher.CHANNEL_PREFIX, "");
        try {
            UUID userId = UUID.fromString(userIdStr);
            JsonNode root = objectMapper.readTree(message.getBody());
            String eventName = root.path("event").asText("message.new");
            JsonNode dataNode = root.has("data") ? root.path("data") : root;
            Object data = objectMapper.treeToValue(dataNode, Object.class);
            sseService.push(userId, eventName, data);
        } catch (IllegalArgumentException ex) {
            log.warn("[CHAT-SUB] bad userId in channel='{}': {}", channel, ex.getMessage());
        } catch (Exception ex) {
            log.error("[CHAT-SUB] failed to process chat event: {}", ex.getMessage());
        }
    }
}
