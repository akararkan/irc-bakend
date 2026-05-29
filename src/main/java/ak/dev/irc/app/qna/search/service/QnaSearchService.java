package ak.dev.irc.app.qna.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.search.document.QnaSearchDocument;
import ak.dev.irc.app.qna.search.repository.QnaSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
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

    private final QnaSearchRepository searchRepo;

    @Async
    public void indexAsync(Question question) {
        if (question == null) return;
        if (question.getDeletedAt() != null) {
            try {
                EsRetry.run(() -> searchRepo.deleteById(QnaSearchDocument.idOf(question.getId())),
                        "[SEARCH] delete (soft) question " + question.getId());
            } catch (Exception e) {
                log.warn("[SEARCH] index question {} failed: {}", question.getId(), e.getMessage());
            }
            return;
        }
        QnaSearchDocument doc = QnaSearchDocument.builder()
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
        try {
            EsRetry.run(() -> searchRepo.save(doc),
                    "[SEARCH] index question " + question.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index question {} failed: {}", question.getId(), e.getMessage());
        }
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
