package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.common.tag.service.ContentTagService;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.qna.search.service.QnaSearchService;
import ak.dev.irc.app.qna.service.QuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Publishes or hides a Q&amp;A question whose verdict arrived after the create
 * request had already returned (MODERATION_ROADMAP.md §5.2 step 4).
 *
 * <p>Approval is not just a status flip: it has to run the announcements
 * {@code createQuestion} deliberately skipped — the {@code QuestionCreatedEvent},
 * the Cassandra tag fan-out, the author's activity record, the @mention pings
 * and the Elasticsearch document. Those live in
 * {@link QuestionService#publishQuestionSideEffects} so approval and create share
 * one implementation.</p>
 *
 * <p>{@code QuestionService} is resolved through an {@link ObjectProvider}
 * rather than injected: the service submits to {@code ContentModerationService},
 * which owns the applier registry, so a constructor reference would close a bean
 * cycle at startup.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnaQuestionModerationApplier implements ModerationApplier {

    private final QuestionRepository questionRepository;
    private final QnaSearchService qnaSearch;
    private final ContentTagService contentTagService;
    private final ObjectProvider<QuestionService> questionService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.QNA_QUESTION;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        Question question = load(moderationCase);
        if (question == null) return;
        if (question.getModerationStatus() == ModerationStatus.APPROVED) {
            return;   // a re-drive of an already-applied case, not a new approval
        }

        question.setModerationStatus(ModerationStatus.APPROVED);
        question.setModerationDecidedAt(LocalDateTime.now());
        questionRepository.save(question);

        questionService.getObject().publishQuestionSideEffects(question.getId());

        log.info("[MODERATION] question {} approved and published", question.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        Question question = load(moderationCase);
        if (question == null) return;
        if (question.getModerationStatus() == ModerationStatus.REJECTED) return;

        question.setModerationStatus(ModerationStatus.REJECTED);
        question.setModerationDecidedAt(LocalDateTime.now());
        questionRepository.save(question);

        // If the question ever published — an SLA fail-open, or an admin
        // reversing an approval — its search document and tag-feed rows have to
        // go, or the text stays discoverable while every read path hides it.
        qnaSearch.deleteAsync(question.getId());
        try {
            contentTagService.untag(question.getId());
        } catch (Exception ex) {
            log.warn("[MODERATION] tag untag failed for question {}: {}",
                    question.getId(), ex.getMessage());
        }

        log.info("[MODERATION] question {} rejected and hidden", question.getId());
    }

    private Question load(ModerationCase moderationCase) {
        try {
            UUID questionId = UUID.fromString(moderationCase.getEntityRef());
            Question question = questionRepository.findById(questionId).orElse(null);
            if (question == null) {
                // Deleted by its author while held. Nothing to apply, and the case
                // row keeps the evidence — which is why the submitted text lives in
                // moderation_case_fields rather than as a pointer at this row.
                log.debug("[MODERATION] question {} gone before verdict applied", questionId);
            }
            return question;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID question ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
