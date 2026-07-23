package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.SaveDraftRequest;
import ak.dev.irc.app.chat.dto.response.DraftResponse;
import ak.dev.irc.app.chat.entity.ConversationDraft;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.repository.ConversationDraftRepository;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-user unsent drafts (Telegram drafts) — save overwrites, sending the
 * message clears it (done in {@code MessageService.send}), and an explicit
 * DELETE clears it too.
 */
@Service
@RequiredArgsConstructor
public class ChatDraftService {

    private final ConversationDraftRepository draftRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;

    @Transactional
    public DraftResponse save(UUID conversationId, UUID userId, SaveDraftRequest req) {
        requireMember(conversationId, userId);
        ConversationDraft d = draftRepo.findByUserIdAndConversationId(userId, conversationId)
                .orElseGet(() -> ConversationDraft.builder()
                        .userId(userId).conversationId(conversationId).build());
        d.setBody(req.getBody());
        d.setMedia(req.getMedia());
        d.setReplyToId(req.getReplyToId());
        return toResponse(draftRepo.save(d));
    }

    @Transactional(readOnly = true)
    public DraftResponse get(UUID conversationId, UUID userId) {
        requireMember(conversationId, userId);
        return draftRepo.findByUserIdAndConversationId(userId, conversationId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Draft", "conversationId", conversationId));
    }

    @Transactional
    public void delete(UUID conversationId, UUID userId) {
        draftRepo.deleteByUserIdAndConversationId(userId, conversationId); // idempotent
    }

    private void requireMember(UUID conversationId, UUID userId) {
        conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
        memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER"));
    }

    private DraftResponse toResponse(ConversationDraft d) {
        return new DraftResponse(d.getConversationId(), d.getBody(), d.getMedia(),
                d.getReplyToId(), d.getUpdatedAt());
    }
}
