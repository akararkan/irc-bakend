package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.entity.MessageStar;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.MessageStarRepository;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Starred / bookmarked messages (WhatsApp "Starred", Telegram "Saved"). Per-user,
 * relational, so "my starred messages" is a cheap indexed lookup. Starring a
 * message you can read is idempotent; the star survives edits and is dropped when
 * the message is deleted for everyone.
 */
@Service
@RequiredArgsConstructor
public class StarService {

    private final MessageStarRepository starRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final ConversationMemberRepository memberRepo;
    private final MessageQueryService messageQueryService;

    @Transactional
    public void star(long messageId, UUID userId) {
        MessageByIdEntity m = requireReadable(messageId, userId);
        if (starRepo.findByUserIdAndMessageId(userId, messageId).isPresent()) return; // idempotent
        starRepo.save(MessageStar.builder()
                .userId(userId).conversationId(m.getConversationId()).messageId(messageId)
                .build());
    }

    @Transactional
    public void unstar(long messageId, UUID userId) {
        starRepo.deleteStar(userId, messageId);
    }

    /** My starred messages, most-recently-starred first, hydrated + ordered. */
    @Transactional(readOnly = true)
    public List<MessageResponse> listStarred(UUID userId, Pageable pageable) {
        List<Long> ids = starRepo.findByUserIdOrderByStarredAtDesc(userId, pageable)
                .map(MessageStar::getMessageId).getContent();
        return messageQueryService.messagesByIds(ids, userId);
    }

    private MessageByIdEntity requireReadable(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        memberRepo.findMember(m.getConversationId(), userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
        return m;
    }
}
