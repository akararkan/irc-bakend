package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PollCounterEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PollCounterRepository extends CassandraRepository<PollCounterEntity, UUID> {

    @Query("SELECT * FROM poll_counters WHERE poll_id = :pollId")
    Optional<PollCounterEntity> findByPollId(@Param("pollId") UUID pollId);
}
