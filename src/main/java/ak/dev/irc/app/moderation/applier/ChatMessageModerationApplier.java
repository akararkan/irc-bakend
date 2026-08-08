package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.search.service.ChatSearchService;
import ak.dev.irc.app.chat.service.MessageService;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Delivers or tombstones a chat message whose verdict arrived after the send had
 * already returned (MODERATION_ROADMAP.md §5.2 step 4). Covers DMs, group
 * messages, Telegram-style channel posts and channel-post comments — they all
 * reach Cassandra through the same {@code MessageService.persist} funnel and so
 * all land here.
 *
 * <p>Approval flips both denormalised rows and then runs the delivery block
 * {@code send} skipped: search indexing, the media gallery, the real inbox
 * preview, channel post accounting, discussion-comment indexing, the
 * {@code message.new} broadcast and the bell notifications. That block lives in
 * {@link MessageService#publishModeratedMessage} so a fan-out that reaches every
 * member of a 200k-subscriber channel has exactly one implementation.</p>
 *
 * <p>The service is resolved through an {@link ObjectProvider} at call time:
 * {@code MessageService} submits to {@code ContentModerationService}, which owns
 * the registry that owns this bean, so a constructor reference would close a bean
 * cycle.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageModerationApplier implements ModerationApplier {

    private final MessageByIdRepository messageByIdRepo;
    private final MessageByConversationRepository messageRepo;
    private final ChatSearchService chatSearch;
    private final ModerationCaseRepository caseRepository;
    private final ObjectProvider<MessageService> messageService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.CHAT_MESSAGE;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        MessageByIdEntity m = load(moderationCase);
        if (m == null) return;
        if (!ChatModeration.held(m.getModerationStatus())) {
            return;   // already released — a re-drive, not a new approval
        }
        if (superseded(moderationCase)) return;

        // null rather than "APPROVED": the write has to be a cell delete so a
        // message living under a disappearing-messages TTL is not left behind as a
        // row carrying one never-expiring column.
        messageRepo.setModerationStatus(m.getConversationId(), m.getBucket(), m.getMessageId(), null);
        messageByIdRepo.setModerationStatus(m.getMessageId(), null);

        // First delivery versus edit-release is decided inside publishModeratedMessage
        // from the row's own `delivered` flag. Neither the case revision nor
        // editedAt can answer it: a media-only message opens no case at all
        // (submission.empty()), so the edit that adds a body is revision 1 on an
        // already-delivered message — while a message held at send and then edited
        // has editedAt set but was never delivered. The two need opposite
        // treatment, and only a durable flag distinguishes them.
        messageService.getObject().publishModeratedMessage(m.getMessageId());
        log.info("[MODERATION] chat message {} approved and delivered", m.getMessageId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        MessageByIdEntity m = load(moderationCase);
        if (m == null) return;
        if (ModerationStatus.REJECTED.name().equals(m.getModerationStatus())) return;
        if (superseded(moderationCase)) return;

        // Same tombstone MessageService.delete writes: the row stays so ordering
        // and pagination survive, the content does not. None of delete's other
        // cleanup applies — a message that never published has no reactions, pins,
        // stars, gallery rows or comment links to unwind.
        messageRepo.tombstone(m.getConversationId(), m.getBucket(), m.getMessageId());
        messageByIdRepo.tombstone(m.getMessageId());
        messageRepo.setModerationStatus(m.getConversationId(), m.getBucket(), m.getMessageId(),
                ModerationStatus.REJECTED.name());
        messageByIdRepo.setModerationStatus(m.getMessageId(), ModerationStatus.REJECTED.name());
        // Only reachable if an SLA fail-open or an admin reversal ever indexed it,
        // but leaving a searchable body behind a hidden message is the one failure
        // that would make the takedown cosmetic.
        chatSearch.deleteAsync(m.getMessageId());
        log.info("[MODERATION] chat message {} rejected and tombstoned", m.getMessageId());
    }

    /**
     * True when a newer case exists for the same message. An edit opens a fresh
     * revision, and that edit can clear inline while the original case is still
     * pending — without this guard the stale verdict would tombstone text that has
     * since been rewritten and approved.
     */
    private boolean superseded(ModerationCase moderationCase) {
        return caseRepository
                .findFirstByEntityTypeAndEntityRefOrderByRevisionDesc(
                        supports(), moderationCase.getEntityRef())
                .map(latest -> latest.getRevision() > moderationCase.getRevision())
                .orElse(false);
    }

    private MessageByIdEntity load(ModerationCase moderationCase) {
        try {
            long messageId = Long.parseLong(moderationCase.getEntityRef());
            MessageByIdEntity m = messageByIdRepo.findById(messageId).orElse(null);
            if (m == null) {
                // Deleted by its sender, or its disappearing TTL expired, while held.
                // Nothing to apply; the case keeps the text, which is the point of
                // storing it in moderation_case_fields rather than pointing at the row.
                log.debug("[MODERATION] chat message {} gone before verdict applied", messageId);
            }
            return m;
        } catch (NumberFormatException badRef) {
            log.warn("[MODERATION] case {} has a non-snowflake message ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
