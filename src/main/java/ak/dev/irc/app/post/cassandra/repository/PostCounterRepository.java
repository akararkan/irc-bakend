package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PostCounterEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only repository for the counters table. Writes go through
 * {@link ak.dev.irc.app.post.cassandra.service.CounterService} which issues
 * {@code UPDATE post_counters SET col = col + ?} — the only way Cassandra
 * accepts counter column writes.
 */
@Repository
public interface PostCounterRepository extends CassandraRepository<PostCounterEntity, UUID> {

    @Query("SELECT * FROM post_counters WHERE post_id = :postId")
    Optional<PostCounterEntity> findByPostId(@Param("postId") UUID postId);
}
