package ak.dev.irc.app.chat.search.repository;

import ak.dev.irc.app.chat.search.document.ChannelSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ChannelSearchRepository extends ElasticsearchRepository<ChannelSearchDocument, String> {
}
