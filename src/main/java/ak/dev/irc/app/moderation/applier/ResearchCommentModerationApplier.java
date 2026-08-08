package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.research.entity.ResearchComment;
import ak.dev.irc.app.research.repository.ResearchCommentRepository;
import ak.dev.irc.app.research.service.impl.ResearchServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Releases or blocks a research comment whose verdict arrived after the write
 * request returned.
 *
 * <p>A held comment is already on disk and already visible to its own author;
 * what approval adds is everything {@code addComment} deferred — the comment and
 * reply counters, the Redis write-through, the researcher's notification, the
 * SSE broadcast and the mention pings. All of that lives in
 * {@link ResearchServiceImpl#publishCommentSideEffects}, so the deferred path
 * and the inline path cannot drift.</p>
 *
 * <p>Rejection only stamps the status: the row stays where it is so its author
 * can still see what they wrote, and every read path already narrows a held
 * comment down to its author and the research owner.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchCommentModerationApplier implements ModerationApplier {

    private final ResearchCommentRepository commentRepository;
    private final ObjectProvider<ResearchServiceImpl> researchService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.RESEARCH_COMMENT;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        ResearchComment comment = load(moderationCase);
        if (comment == null) return;
        if (!comment.isHeldForModeration()) {
            // A re-drive after a failed apply. Running the side effects again
            // would double-count the comment and re-notify the researcher.
            return;
        }
        if (comment.getDeletedAt() != null) {
            // The author deleted it while it was held; releasing it now would
            // resurrect a comment nobody can see into the counters.
            comment.setModerationStatus(ModerationStatus.APPROVED);
            comment.setModerationDecidedAt(LocalDateTime.now());
            commentRepository.save(comment);
            return;
        }

        comment.setModerationStatus(ModerationStatus.APPROVED);
        comment.setModerationDecidedAt(LocalDateTime.now());
        commentRepository.save(comment);

        researchService.getObject().publishCommentSideEffects(comment);

        log.info("[MODERATION] research comment {} approved and published", comment.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        ResearchComment comment = load(moderationCase);
        if (comment == null) return;
        if (comment.getModerationStatus() == ModerationStatus.REJECTED) return;

        boolean wasCounted = !comment.isHeldForModeration();

        comment.setModerationStatus(ModerationStatus.REJECTED);
        comment.setModerationDecidedAt(LocalDateTime.now());
        commentRepository.save(comment);

        if (wasCounted) {
            // Only reachable when the comment had already been released — a
            // fail-open SLA verdict, or an admin reversing an approval. Take the
            // counters back down, or the thread header keeps advertising a
            // comment no reader can open.
            researchService.getObject().unpublishCommentCounters(comment);
        }

        log.info("[MODERATION] research comment {} rejected and hidden", comment.getId());
    }

    private ResearchComment load(ModerationCase moderationCase) {
        try {
            UUID commentId = UUID.fromString(moderationCase.getEntityRef());
            ResearchComment comment = commentRepository.findById(commentId).orElse(null);
            if (comment == null) {
                log.debug("[MODERATION] research comment {} gone before verdict applied", commentId);
            }
            return comment;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID research comment ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
