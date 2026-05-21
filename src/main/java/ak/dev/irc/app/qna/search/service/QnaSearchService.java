package ak.dev.irc.app.qna.search.service;

import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.search.document.QnaSearchDocument;
import ak.dev.irc.app.qna.search.repository.QnaSearchRepository;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch-backed search for Q&A questions.
 *
 * Indexing is async — never blocks the write path. Canonical store is Postgres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QnaSearchService {

    private final QnaSearchRepository     searchRepo;
    private final ElasticsearchOperations esOps;

    @Async
    public void indexAsync(Question question) {
        if (question == null) return;
        try {
            if (question.getDeletedAt() != null) {
                searchRepo.deleteById(QnaSearchDocument.idOf(question.getId()));
                return;
            }
            QnaSearchDocument doc = QnaSearchDocument.builder()
                    .id(QnaSearchDocument.idOf(question.getId()))
                    .title(question.getTitle())
                    .body(question.getBody())
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
            searchRepo.save(doc);
        } catch (Exception e) {
            log.warn("[SEARCH] index question {} failed: {}", question.getId(), e.getMessage());
        }
    }

    @Async
    public void deleteAsync(UUID questionId) {
        if (questionId == null) return;
        try {
            searchRepo.deleteById(questionId.toString());
        } catch (Exception e) {
            log.warn("[SEARCH] delete question {} failed: {}", questionId, e.getMessage());
        }
    }

    /**
     * Full-text search over question title + body + author fields.
     * Returns question UUIDs ranked by BM25 — caller hydrates from Postgres.
     */
    public List<UUID> search(String query, int page, int size) {
        if (query == null || query.isBlank()) return List.of();

        Query esQuery = Query.of(q -> q.bool(b -> b
                .must(m -> m.multiMatch(mm -> mm
                        .query(query)
                        .fields("title^4", "body^2",
                                "authorName", "authorUsername")))));

        NativeQuery native_ = NativeQuery.builder()
                .withQuery(esQuery)
                .withPageable(PageRequest.of(page, size))
                .build();

        SearchHits<QnaSearchDocument> hits =
                esOps.search(native_, QnaSearchDocument.class);

        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(QnaSearchDocument::getId)
                .map(UUID::fromString)
                .toList();
    }
}
