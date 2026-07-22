package ak.dev.irc.app.chat.realtime;

import ak.dev.irc.app.chat.dto.response.ConversationResponse;
import ak.dev.irc.app.chat.dto.response.MessageRequestResponse;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The single realtime event shape carried over the chat SSE stream — one class
 * with all-nullable fields dispatched on {@link #eventType}, mirroring the
 * project's existing {@code PostRealtimeEvent}/{@code StoryTrayEvent} convention.
 *
 * <p>Counter values are intentionally <b>omitted</b>: per the platform's
 * delta-not-counts realtime model, the client applies {@code +1/-1} to its local
 * unread/reaction counters when it sees an event, avoiding stale re-reads.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRealtimeEvent {

    private ChatRealtimeEventType eventType;
    private UUID conversationId;

    // message.new / (edited carries the full message too for convenience)
    private MessageResponse message;

    // message.edited / message.deleted / message.reaction / receipt.delivered
    private Long messageId;
    private String body;
    private Instant editedAt;

    // message.reaction
    private String emoji;
    private Boolean added;

    // receipt.read / typing / presence / member.changed — the subject user
    private UUID userId;
    private Long lastReadMessageId;
    private Boolean isTyping;
    private String presenceStatus;   // "online" | "offline"
    private Long lastSeenEpochMs;

    // conversation.updated
    private ConversationResponse conversation;

    // member.changed
    private String memberChange;     // ADDED | REMOVED | LEFT | PROMOTED | DEMOTED | RESTRICTED | UNRESTRICTED
    private String role;

    // request.new
    private MessageRequestResponse request;

    @Builder.Default
    private Instant timestamp = Instant.now();
}
