package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PollVoterByChoiceEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PollVoterByChoiceRepository extends CassandraRepository<PollVoterByChoiceEntity, MapId> {

    @Query("SELECT * FROM poll_voters_by_choice WHERE poll_id = :pollId AND choice = :choice LIMIT :pageSize")
    List<PollVoterByChoiceEntity> firstPage(@Param("pollId") UUID pollId,
                                            @Param("choice") String choice,
                                            @Param("pageSize") int pageSize);

    @Query("DELETE FROM poll_voters_by_choice WHERE poll_id = :pollId AND choice = :choice " +
           "AND voted_at = :votedAt AND voter_id = :voterId")
    void delete(@Param("pollId") UUID pollId,
                @Param("choice") String choice,
                @Param("votedAt") Instant votedAt,
                @Param("voterId") UUID voterId);
}
