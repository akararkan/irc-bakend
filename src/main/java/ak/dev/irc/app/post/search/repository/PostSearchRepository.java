package ak.dev.irc.app.post.search.repository;

import ak.dev.irc.app.post.search.document.PostSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PostSearchRepository extends ElasticsearchRepository<PostSearchDocument, String> {}
