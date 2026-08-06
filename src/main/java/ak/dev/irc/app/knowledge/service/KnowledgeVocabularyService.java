package ak.dev.irc.app.knowledge.service;

import ak.dev.irc.app.knowledge.entity.Madhhab;
import ak.dev.irc.app.knowledge.entity.Topic;
import ak.dev.irc.app.knowledge.repository.MadhhabRepository;
import ak.dev.irc.app.knowledge.repository.TopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cached reads of the knowledge taxonomy (topics + madhhabs). These are
 * tiny, effectively-static vocabularies hit by every profile editor and
 * specialization picker — a Redis cache turns the repeated full-table read
 * into one DB hit per TTL window. Both vocabularies change only via
 * migrations, so a long TTL is safe.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeVocabularyService {

    private final TopicRepository   topicRepository;
    private final MadhhabRepository madhhabRepository;

    @Cacheable("knowledge-topics")
    @Transactional(readOnly = true)
    public List<Topic> allTopics() {
        // Retired rows leave the pickers but stay findById-resolvable, so
        // existing profile references never orphan (admin soft-retire).
        return topicRepository.findAll().stream()
                .filter(t -> t.getArchivedAt() == null)
                .toList();
    }

    @Cacheable("knowledge-madhhabs")
    @Transactional(readOnly = true)
    public List<Madhhab> allMadhhabs() {
        return madhhabRepository.findAll().stream()
                .filter(m -> m.getArchivedAt() == null)
                .toList();
    }
}
