package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The transactional insert for a DIRECT conversation, kept in its own bean so the
 * caller can run it through the Spring proxy and catch a unique-constraint
 * violation cleanly (a poisoned persistence context can't be reused, so the
 * race-recovery re-fetch must happen outside this transaction).
 */
@Service
@RequiredArgsConstructor
public class ChatConversationFactory {

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;

    /**
     * Insert a new DIRECT conversation and both member rows. Throws
     * {@link org.springframework.dao.DataIntegrityViolationException} if the
     * {@code direct_key} was concurrently taken — the caller recovers by
     * re-fetching the winner.
     */
    @Transactional
    public UUID createDirect(UUID creatorId, UUID recipientId, String directKey) {
        Conversation c = conversationRepo.saveAndFlush(Conversation.builder()
                .type(ConversationType.DIRECT)
                .ownerId(creatorId)
                .directKey(directKey)
                .memberCount(2)
                .build());
        // DIRECT participants are both plain members (roles apply only to groups).
        memberRepo.save(ConversationMember.of(c, creatorId, MemberRole.MEMBER));
        memberRepo.save(ConversationMember.of(c, recipientId, MemberRole.MEMBER));
        return c.getId();
    }
}
