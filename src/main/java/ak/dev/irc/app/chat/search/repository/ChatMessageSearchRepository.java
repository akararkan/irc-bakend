package ak.dev.irc.app.chat.search.repository;

import ak.dev.irc.app.chat.search.document.ChatMessageDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageSearchRepository
        extends ElasticsearchRepository<ChatMessageDocument, String> {}
