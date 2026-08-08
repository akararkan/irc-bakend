package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.search.service.ChannelSearchService;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.repository.ModerationCaseRepository;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Releases or hides a channel (or group) whose title/description was held at
 * creation time. Both surfaces share this applier because both are the same unit
 * of text on the same {@code conversations} row — a group is simply a channel
 * nobody can discover.
 *
 * <p>Only the held-on-<em>create</em> case reaches here. Channel and group
 * <em>edits</em> follow the roadmap's strict policy (§5.5) and are refused
 * outright when the verdict is anything but a clear pass, so an edit never leaves
 * unpublished text on a live channel for an applier to resolve. Such a case still
 * settles and still drives this applier — and correctly finds nothing to do,
 * because the wording it scored was never written.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelModerationApplier implements ModerationApplier {

    private final ConversationRepository conversationRepo;
    private final ChannelSearchService channelSearch;
    private final ModerationCaseRepository caseRepository;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.CHANNEL;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        Conversation c = load(moderationCase);
        if (c == null) return;
        if (!ChatModeration.held(c.getModerationStatus())) return;   // re-drive
        if (superseded(moderationCase)) return;

        c.setModerationStatus(null);
        conversationRepo.save(c);
        // Deferred publication for a channel is exactly one thing: becoming
        // discoverable. Members could always see it — it is their own channel.
        channelSearch.indexAsync(c);
        log.info("[MODERATION] channel {} approved and listed", c.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        Conversation c = load(moderationCase);
        if (c == null) return;
        // The strict §5.5 edit policy refuses a title/description that does not
        // clear inline, so the row still carries the PREVIOUSLY APPROVED wording
        // while that refused revision goes on to settle. Rejecting it must not
        // de-list a live channel over text nobody ever saw — only content that is
        // actually held (i.e. whose stored wording is the wording under review)
        // may be taken down here. An admin reversing an already-applied approval
        // cannot reach this method at all: ContentModerationService.applyIfNeeded
        // short-circuits on appliedAt.
        if (!ChatModeration.held(c.getModerationStatus())) return;
        if (ModerationStatus.REJECTED.name().equals(c.getModerationStatus())) return;
        if (superseded(moderationCase)) return;

        c.setModerationStatus(ModerationStatus.REJECTED.name());
        conversationRepo.save(c);
        // The channel is not deleted — its owner and subscribers keep their message
        // history, and an owner who disputes the call has something to appeal about.
        // What it loses is reach: no directory entry, no search hit.
        channelSearch.deleteAsync(c.getId());
        log.info("[MODERATION] channel {} rejected and de-listed", c.getId());
    }

    /** A later edit that cleared inline supersedes an older pending verdict. */
    private boolean superseded(ModerationCase moderationCase) {
        return caseRepository
                .findFirstByEntityTypeAndEntityRefOrderByRevisionDesc(
                        supports(), moderationCase.getEntityRef())
                .map(latest -> latest.getRevision() > moderationCase.getRevision())
                .orElse(false);
    }

    private Conversation load(ModerationCase moderationCase) {
        try {
            UUID id = UUID.fromString(moderationCase.getEntityRef());
            Conversation c = conversationRepo.findById(id).orElse(null);
            if (c == null || c.getDeletedAt() != null) {
                log.debug("[MODERATION] conversation {} gone before verdict applied", id);
                return null;
            }
            return c;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID conversation ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
