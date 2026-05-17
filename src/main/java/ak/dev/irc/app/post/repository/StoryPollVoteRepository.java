package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.StoryPollVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoryPollVoteRepository extends JpaRepository<StoryPollVote, UUID> {
    Optional<StoryPollVote> findByPollIdAndVoterId(UUID pollId, UUID voterId);
    boolean existsByPollIdAndVoterId(UUID pollId, UUID voterId);
}
