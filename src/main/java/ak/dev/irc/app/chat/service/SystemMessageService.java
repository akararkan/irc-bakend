package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.chat.enums.SystemEventType;
import ak.dev.irc.app.chat.mapper.ChatMapper;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes {@link MessageType#SYSTEM} timeline entries for group lifecycle events
 * (member added, title changed, …). They are ordinary rows in the message log so
 * they appear inline, paginate normally, and update the inbox preview — no
 * separate mechanism. Shared by {@code ConversationService} and
 * {@code GroupMemberService}.
 */
@Service
@RequiredArgsConstructor
public class SystemMessageService {

    private final SnowflakeIdGenerator snowflake;
    private final MessageByConversationRepository messageRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatMapper mapper;
    private final UserRepository userRepository;

    /**
     * Append a SYSTEM message and broadcast it to every readable member.
     *
     * @return the Snowflake id of the system message.
     */
    public long write(UUID conversationId, SystemEventType event, UUID actorId, String text) {
        long id = snowflake.nextId();
        int bucket = ChatBuckets.bucketOf(id);
        Instant now = Instant.now();

        MessageByConversationEntity row = MessageByConversationEntity.builder()
                .conversationId(conversationId).bucket(bucket).messageId(id)
                .senderId(actorId).type(MessageType.SYSTEM.name())
                .body(text).systemEvent(event.name())
                .deleted(false).createdAt(now)
                .build();
        messageRepo.save(row);
        messageByIdRepo.save(MessageByIdEntity.builder()
                .messageId(id).conversationId(conversationId).bucket(bucket)
                .senderId(actorId).type(MessageType.SYSTEM.name())
                .body(text).systemEvent(event.name())
                .deleted(false).createdAt(now)
                .build());

        conversationRepo.advanceLastMessage(conversationId, id,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), text);

        List<UUID> recipients = memberRepo.findReadableMemberIds(conversationId);
        Map<UUID, User> users = actorId == null ? Map.of()
                : userRepository.findActiveByIdIn(List.of(actorId)).stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MESSAGE_NEW)
                .conversationId(conversationId)
                .message(mapper.toMessage(row, users, List.of(), null))
                .build();
        broadcaster.broadcast(recipients, evt);
        return id;
    }
}
