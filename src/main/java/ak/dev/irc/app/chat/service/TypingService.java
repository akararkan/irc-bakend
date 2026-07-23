package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.permission.ChatRelationshipService;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.common.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Ephemeral typing indicators. Never stored beyond a short Redis TTL that
 * auto-clears if the client simply stops sending — no explicit "stopped typing"
 * is required. Suppressed for still-pending message requests so neither party
 * learns the other is present before acceptance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TypingService {

    private static final Duration TYPING_TTL = Duration.ofSeconds(6);

    private final StringRedisTemplate redis;
    private final ConversationMemberRepository memberRepo;
    private final ChatRelationshipService relationships;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatSettingsService chatSettings;

    public void handleTyping(UUID conversationId, UUID senderId, boolean isTyping) {
        // Must be an active member to signal typing.
        var member = memberRepo.findMember(conversationId, senderId)
                .filter(m -> m.getStatus() != null && m.isActive())
                .orElseThrow(() -> new ForbiddenException(
                        "You are not an active member of this conversation.", "NOT_A_MEMBER"));

        String key = "chat:typing:" + conversationId + ":" + senderId;
        try {
            if (isTyping) redis.opsForValue().set(key, "1", TYPING_TTL);
            else redis.delete(key);
        } catch (Exception e) {
            log.debug("[TYPING] redis op failed: {}", e.getMessage());
        }

        // Suppress while a request is pending or the thread is restricted, so the
        // other party never learns the sender is around; also honour the sender's
        // own "typing indicators off" preference.
        if (relationships.suppressEphemeral(conversationId, senderId)) return;
        if (!chatSettings.typingEnabled(senderId)) return;

        List<UUID> recipients = memberRepo.findActiveMemberIds(conversationId);
        ChatRealtimeEvent event = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.TYPING)
                .conversationId(conversationId)
                .userId(senderId)
                .isTyping(isTyping)
                .build();
        broadcaster.broadcastExcept(recipients, senderId, event);
    }
}
