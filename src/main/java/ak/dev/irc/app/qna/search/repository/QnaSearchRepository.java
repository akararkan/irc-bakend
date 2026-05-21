package ak.dev.irc.app.qna.search.repository;

import ak.dev.irc.app.qna.search.document.QnaSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QnaSearchRepository
        extends ElasticsearchRepository<QnaSearchDocument, String> {}
