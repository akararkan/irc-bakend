package ak.dev.irc.app.qna.search.repository;

import ak.dev.irc.app.qna.search.document.AnswerSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface AnswerSearchRepository extends ElasticsearchRepository<AnswerSearchDocument, String> {

    /** Purge every answer doc of a deleted question (delete-by-query on the keyword field). */
    void deleteByQuestionId(String questionId);
}
