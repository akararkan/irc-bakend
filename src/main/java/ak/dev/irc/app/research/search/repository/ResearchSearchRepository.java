package ak.dev.irc.app.research.search.repository;

import ak.dev.irc.app.research.search.document.ResearchSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResearchSearchRepository
        extends ElasticsearchRepository<ResearchSearchDocument, String> {}
