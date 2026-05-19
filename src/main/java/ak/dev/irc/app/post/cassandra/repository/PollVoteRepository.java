package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PollVoteEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PollVoteRepository extends CassandraRepository<PollVoteEntity, MapId> {

    @Query("SELECT * FROM poll_votes_by_poll_user WHERE poll_id = :pollId AND voter_id = :voterId")
    Optional<PollVoteEntity> find(@Param("pollId") UUID pollId,
                                  @Param("voterId") UUID voterId);
}
