package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.StoryPoll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoryPollRepository extends JpaRepository<StoryPoll, UUID> {
    Optional<StoryPoll> findByStoryId(UUID storyId);
}
