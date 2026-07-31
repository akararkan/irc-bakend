package ak.dev.irc.app.qna.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.search.document.AnswerSearchDocument;
import ak.dev.irc.app.qna.search.repository.AnswerSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch-backed indexer for Q&amp;A <em>answers</em>
 * ({@code irc-answers}).
 *
 * Index-only: query-time search is served by the unified
 * {@code /api/v1/search?types=ANSWER} endpoint ({@code GlobalSearchService}).
 * Mirrors {@code QnaSearchService} (which owns the question index): async,
 * best-effort, Postgres canonical. Accept/unaccept re-indexes so the
 * {@code accepted} ranking boost stays current.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerSearchService {

    private final AnswerSearchRepository   searchRepo;
    private final QuestionAnswerRepository answerRepo;
    private final ElasticsearchOperations  esOps;

    @Async
    public void indexAsync(QuestionAnswer answer) {
        if (answer == null) return;
        if (answer.getDeletedAt() != null) {
            deleteQuietly(answer.getId(), "soft-deleted");
            return;
        }
        try {
            EsRetry.run(() -> searchRepo.save(buildDoc(answer)),
                    "[SEARCH] index answer " + answer.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index answer {} failed: {}", answer.getId(), e.getMessage());
        }
    }

    @Async
    public void deleteAsync(UUID answerId) {
        deleteQuietly(answerId, "deleted");
    }

    /** Purge every answer doc of a deleted question so no orphans linger. */
    @Async
    public void deleteByQuestionAsync(UUID questionId) {
        if (questionId == null) return;
        try {
            EsRetry.run(() -> searchRepo.deleteByQuestionId(questionId.toString()),
                    "[SEARCH] purge answers of question " + questionId);
        } catch (Exception e) {
            log.warn("[SEARCH] purge answers of question {} failed: {}", questionId, e.getMessage());
        }
    }

    private void deleteQuietly(UUID answerId, String why) {
        if (answerId == null) return;
        try {
            EsRetry.run(() -> searchRepo.deleteById(answerId.toString()),
                    "[SEARCH] delete (" + why + ") answer " + answerId);
        } catch (Exception e) {
            log.warn("[SEARCH] delete answer {} failed: {}", answerId, e.getMessage());
        }
    }

    /** One-shot reindex of every live answer (reanswers included). Synchronous. */
    @Transactional(readOnly = true)
    public ReindexSummary reindexAll(boolean dropFirst) {
        Instant start = Instant.now();
        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(AnswerSearchDocument.class).delete(),
                        "[SEARCH] drop irc-answers");
                dropped = true;
            } catch (Exception e) {
                log.warn("[SEARCH] irc-answers drop best-effort failed (often harmless): {}", e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(AnswerSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-answers mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-answers mapping recreate failed: {}", e.getMessage());
            }
        }

        final int PAGE_SIZE = 100;
        long indexed = 0;
        int pages = 0, page = 0;
        while (true) {
            Page<QuestionAnswer> slice =
                    answerRepo.findByDeletedAtIsNull(PageRequest.of(page, PAGE_SIZE));
            if (slice.isEmpty()) break;

            List<AnswerSearchDocument> docs = new ArrayList<>(slice.getNumberOfElements());
            for (QuestionAnswer a : slice.getContent()) docs.add(buildDoc(a));
            try {
                EsRetry.run(() -> searchRepo.saveAll(docs),
                        "[SEARCH] reindex answers page " + page);
                indexed += docs.size();
                pages++;
            } catch (Exception e) {
                log.warn("[SEARCH] answer reindex page {} failed ({} docs skipped): {}",
                        page, docs.size(), e.getMessage());
            }
            if (!slice.hasNext()) break;
            page++;
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d answer(s) across %d page(s)%s.",
                indexed, pages, dropped ? " (index was dropped first)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexSummary(dropped, indexed, pages, ms, note);
    }

    private static AnswerSearchDocument buildDoc(QuestionAnswer a) {
        return AnswerSearchDocument.builder()
                .id(AnswerSearchDocument.idOf(a.getId()))
                .questionId(a.getQuestion() == null ? null : a.getQuestion().getId().toString())
                .questionTitle(a.getQuestion() == null ? null : a.getQuestion().getTitle())
                .body(a.getBody())
                .authorId(a.getAuthor() == null ? null : a.getAuthor().getId().toString())
                .authorName(a.getAuthor() == null ? null : a.getAuthor().getFullName())
                .authorUsername(a.getAuthor() == null ? null : a.getAuthor().getUsername())
                .accepted(a.isAccepted())
                .status("ACTIVE")
                .reactionCount(a.getReactionCount())
                .createdAt(a.getCreatedAt() == null ? null
                        : a.getCreatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }
}
