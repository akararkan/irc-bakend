package ak.dev.irc.app.knowledge.repository;

import ak.dev.irc.app.knowledge.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TopicRepository extends JpaRepository<Topic, Integer> {}
