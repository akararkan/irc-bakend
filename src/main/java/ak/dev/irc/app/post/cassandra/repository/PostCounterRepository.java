package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PostCounterEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
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

    /**
     * Bulk counter fetch for a feed page. Pulls all counters in one driver
     * round-trip via a multi-partition IN. Bounded callers only — feeds size
     * 20–50 are fine; do not pass arbitrarily large sets.
     *
     * Uses a derived query (not @Query) so Spring Data Cassandra's QueryBuilder
     * handles IN-clause binding correctly. A raw @Query prepared statement causes
     * the driver to infer the bind variable as SET<uuid>, but SDC binds a Java
     * Collection as LIST (DefaultListType), triggering a ClassCastException.
     */
    List<PostCounterEntity> findAllByPostIdIn(Collection<UUID> postIds);
}
