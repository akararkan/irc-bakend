package ak.dev.irc.app.qna.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.qna.search.document.QnaSearchDocument;
import ak.dev.irc.app.qna.search.repository.QnaSearchRepository;
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
 * Elasticsearch-backed indexer for Q&A questions.
 *
 * Index-only: query-time search is served by the unified
 * {@code /api/v1/search} endpoint (see {@code GlobalSearchService}). This
 * class owns the {@code irc-qna} index and keeps it in sync with Postgres.
 *
 * Indexing is async — never blocks the write path. Canonical store is Postgres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QnaSearchService {

    private final QnaSearchRepository     searchRepo;
    private final QuestionRepository      questionRepo;
    private final ElasticsearchOperations esOps;

    @Async
    public void indexAsync(Question question) {
        if (question == null) return;
        // Soft-deleted and not-yet-cleared questions are both "must not be
        // findable". Handling the moderation case here rather than at every call
        // site means an edit that drops an approved question back into review
        // also pulls its stale document, which skipping the call would not.
        if (question.getDeletedAt() != null || question.isModerationHeld()) {
            String why = question.getDeletedAt() != null ? "soft" : "held";
            try {
                EsRetry.run(() -> searchRepo.deleteById(QnaSearchDocument.idOf(question.getId())),
                        "[SEARCH] delete (" + why + ") question " + question.getId());
            } catch (Exception e) {
                log.warn("[SEARCH] index question {} failed: {}", question.getId(), e.getMessage());
            }
            return;
        }
        try {
            EsRetry.run(() -> searchRepo.save(buildDoc(question)),
                    "[SEARCH] index question " + question.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index question {} failed: {}", question.getId(), e.getMessage());
        }
    }

    private static QnaSearchDocument buildDoc(Question question) {
        return QnaSearchDocument.builder()
                .id(QnaSearchDocument.idOf(question.getId()))
                .title(question.getTitle())
                .body(question.getBody())
                .tags(question.getTags() == null ? null : new ArrayList<>(question.getTags()))
                .keywords(question.getKeywords())
                .authorId(question.getAuthor() == null ? null
                        : question.getAuthor().getId().toString())
                .authorName(question.getAuthor() == null ? null
                        : question.getAuthor().getFullName())
                .authorUsername(question.getAuthor() == null ? null
                        : question.getAuthor().getUsername())
                .status(question.getStatus() == null ? null
                        : question.getStatus().name())
                .answerCount(question.getAnswerCount())
                .viewCount(question.getViewCount())
                .saveCount(question.getSaveCount())
                .createdAt(question.getCreatedAt() == null ? null
                        : question.getCreatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }

    /**
     * One-shot reindex of every live question. With {@code dropFirst=true}
     * also repairs a legacy dynamically-created mapping (text lifecycle
     * fields silently break the status term filters) by recreating the
     * index from the entity mapping before re-emitting.
     */
    @Transactional(readOnly = true)
    public ReindexSummary reindexAll(boolean dropFirst) {
        Instant start = Instant.now();
        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(QnaSearchDocument.class).delete(),
                        "[SEARCH] drop irc-qna");
                dropped = true;
            } catch (Exception e) {
                log.warn("[SEARCH] irc-qna drop best-effort failed (often harmless): {}", e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(QnaSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-qna mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-qna mapping recreate failed: {}", e.getMessage());
            }
        }

        final int PAGE_SIZE = 100;
        long indexed = 0;
        int pages = 0, page = 0;
        while (true) {
            Page<Question> slice = questionRepo.findIndexable(PageRequest.of(page, PAGE_SIZE));
            if (slice.isEmpty()) break;
            List<QnaSearchDocument> docs = new ArrayList<>(slice.getNumberOfElements());
            for (Question q : slice.getContent()) docs.add(buildDoc(q));
            try {
                EsRetry.run(() -> searchRepo.saveAll(docs),
                        "[SEARCH] reindex questions page " + page);
                indexed += docs.size();
                pages++;
            } catch (Exception e) {
                log.warn("[SEARCH] question reindex page {} failed ({} docs skipped): {}",
                        page, docs.size(), e.getMessage());
            }
            if (!slice.hasNext()) break;
            page++;
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d question(s) across %d page(s)%s.",
                indexed, pages, dropped ? " (index was dropped and mapping recreated)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexSummary(dropped, indexed, pages, ms, note);
    }

    @Async
    public void deleteAsync(UUID questionId) {
        if (questionId == null) return;
        try {
            EsRetry.run(() -> searchRepo.deleteById(questionId.toString()),
                    "[SEARCH] delete question " + questionId);
        } catch (Exception e) {
            log.warn("[SEARCH] delete question {} failed: {}", questionId, e.getMessage());
        }
    }
}
