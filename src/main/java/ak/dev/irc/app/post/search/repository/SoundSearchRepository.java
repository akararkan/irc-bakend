package ak.dev.irc.app.post.search.repository;

import ak.dev.irc.app.post.search.document.SoundSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface SoundSearchRepository extends ElasticsearchRepository<SoundSearchDocument, String> {
}
