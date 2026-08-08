package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.qna.search.service.AnswerSearchService;
import ak.dev.irc.app.qna.service.QuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Publishes or hides a Q&amp;A answer — top-level or reanswer, they are the same
 * row — whose verdict arrived after the create request had already returned.
 *
 * <p>Approval runs the block {@code addAnswer} skipped: the answer / reply
 * counters, the {@code QuestionAnsweredEvent}, the activity record, the @mention
 * pings, the SSE push on the question's stream and the Elasticsearch document.
 * That block lives in {@link QuestionService#publishAnswerSideEffects}, which
 * moves the counters only on an answer's first publication, so re-driving a
 * failed apply cannot double-count.</p>
 *
 * <p>Rejection unwinds the counters when the answer had in fact been published —
 * an SLA fail-open, or an admin reversing an approval — because a question
 * claiming three answers while showing two is a bug users notice.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QnaAnswerModerationApplier implements ModerationApplier {

    private final QuestionAnswerRepository answerRepository;
    private final QuestionRepository questionRepository;
    private final AnswerSearchService answerSearch;
    private final CounterCache counterCache;
    private final ObjectProvider<QuestionService> questionService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.QNA_ANSWER;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        QuestionAnswer answer = load(moderationCase);
        if (answer == null) return;
        if (answer.getModerationStatus() == ModerationStatus.APPROVED) return;

        answer.setModerationStatus(ModerationStatus.APPROVED);
        answer.setModerationDecidedAt(LocalDateTime.now());
        answerRepository.save(answer);

        questionService.getObject().publishAnswerSideEffects(answer.getId());

        log.info("[MODERATION] answer {} approved and published", answer.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        QuestionAnswer answer = load(moderationCase);
        if (answer == null) return;
        if (answer.getModerationStatus() == ModerationStatus.REJECTED) return;

        boolean wasPublished = answer.getPublishedAt() != null;
        answer.setModerationStatus(ModerationStatus.REJECTED);
        answer.setModerationDecidedAt(LocalDateTime.now());
        // Clearing the stamp keeps the counters symmetric: if an admin later
        // reverses this rejection, publishAnswerSideEffects treats it as a first
        // publication again and puts back exactly what was taken away here.
        answer.setPublishedAt(null);
        answerRepository.save(answer);

        if (wasPublished) {
            // Postgres is only half of each counter: QuestionMapper reads them
            // through CounterCache (cache-aside, Redis wins whenever the field is
            // present, and the TTL is refreshed on every write). Adjusting the
            // column without re-seeding Redis would leave every reader showing the
            // pre-rejection number indefinitely — the same reason deleteAnswer
            // sets the cache right after its own decrement.
            if (answer.getParentAnswer() == null) {
                UUID questionId = answer.getQuestion().getId();
                questionRepository.adjustAnswerCount(questionId, -1);
                Long fresh = questionRepository.findAnswerCount(questionId);
                counterCache.set(CounterCache.Kind.QUESTION, questionId,
                        CounterCache.F_ANSWERS, fresh == null ? 0L : fresh);
            } else {
                UUID parentId = answer.getParentAnswer().getId();
                answerRepository.updateReplyCount(parentId, -1);
                Long fresh = answerRepository.findReplyCount(parentId);
                counterCache.set(CounterCache.Kind.ANSWER, parentId,
                        CounterCache.F_REPLIES, fresh == null ? 0L : fresh);
            }
        }

        answerSearch.deleteAsync(answer.getId());

        log.info("[MODERATION] answer {} rejected and hidden (counters unwound: {})",
                answer.getId(), wasPublished);
    }

    private QuestionAnswer load(ModerationCase moderationCase) {
        try {
            UUID answerId = UUID.fromString(moderationCase.getEntityRef());
            QuestionAnswer answer = answerRepository.findById(answerId).orElse(null);
            if (answer == null) {
                log.debug("[MODERATION] answer {} gone before verdict applied", answerId);
            }
            return answer;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID answer ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}
