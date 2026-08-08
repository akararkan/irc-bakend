package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.repository.LiveStreamRepository;
import ak.dev.irc.app.chat.service.LiveStreamService;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Releases or hides a live stream whose title/description was held at go-live.
 *
 * <p>The deferred side effect here is the "@host is live" follower fan-out, which
 * {@code LiveStreamService.start} skips for a held title. That fan-out copies the
 * raw title into a persisted notification row and a push payload for up to 50,000
 * followers, so it is the single publication step that genuinely cannot be taken
 * back — everything else about a held stream (the media path, playback, the host's
 * own view) is unaffected, because it was the text that was in question, never the
 * broadcast.</p>
 *
 * <p>Idempotent by the held check: the sweeper re-drives failed applies, and a
 * second fan-out would notify every follower twice.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamMetaModerationApplier implements ModerationApplier {

    private final LiveStreamRepository streamRepo;
    /** Resolved at call time — {@code LiveStreamService} submits to
     *  {@code ContentModerationService}, which transitively owns this bean. */
    private final ObjectProvider<LiveStreamService> liveStreamService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.STREAM_META;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        LiveStream s = load(moderationCase);
        if (s == null) return;
        if (!ChatModeration.held(s.getModerationStatus())) return;   // re-drive

        s.setModerationStatus(null);
        streamRepo.save(s);
        // No superseded-revision guard is needed: a stream edit follows the strict
        // §5.5 policy and is refused unless it clears inline, so a later revision
        // can only ever be one that already approved this same text.
        liveStreamService.getObject().fanoutHeldStreamStarted(s.getId());
        log.info("[MODERATION] stream {} metadata approved", s.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        LiveStream s = load(moderationCase);
        if (s == null) return;
        // A stream edit follows the strict §5.5 policy: an unresolved verdict
        // refuses the change and the previously approved metadata keeps serving.
        // That refused revision still settles, and if it settles REJECTED it must
        // not blank the title of a live broadcast whose stored wording was never
        // the wording under review. Only genuinely held metadata is taken down.
        if (!ChatModeration.held(s.getModerationStatus())) return;
        if (ModerationStatus.REJECTED.name().equals(s.getModerationStatus())) return;

        s.setModerationStatus(ModerationStatus.REJECTED.name());
        streamRepo.save(s);
        // The broadcast is left running deliberately: only the metadata was judged,
        // and cutting a live stream off mid-sentence over a title is a far heavier
        // action than the verdict supports. What the stream loses is its title and
        // description for everyone but its host, plus its place in the directory —
        // both enforced on the read path, so this needs no further writes. Live
        // streams are not indexed in Elasticsearch, so there is nothing to de-index.
        log.info("[MODERATION] stream {} metadata rejected and redacted", s.getId());
    }

    private LiveStream load(ModerationCase moderationCase) {
        try {
            UUID id = UUID.fromString(moderationCase.getEntityRef());
            LiveStream s = streamRepo.findById(id).orElse(null);
            if (s == null) {
                log.debug("[MODERATION] stream {} gone before verdict applied", id);
            }
            return s;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID stream ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
