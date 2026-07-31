package ak.dev.irc.app.user.search.repository;

import ak.dev.irc.app.user.search.document.UserSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface UserSearchRepository extends ElasticsearchRepository<UserSearchDocument, String> {
}
