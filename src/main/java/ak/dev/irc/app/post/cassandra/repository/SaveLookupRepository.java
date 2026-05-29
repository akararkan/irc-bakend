package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.SaveLookupEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SaveLookupRepository extends CassandraRepository<SaveLookupEntity, MapId> {

    @Query("SELECT * FROM saves_by_post_user WHERE post_id = :postId AND user_id = :userId")
    Optional<SaveLookupEntity> find(@Param("postId") UUID postId,
                                    @Param("userId") UUID userId);

    /**
     * Bulk "which of these posts has user U saved?" — single driver round-trip.
     * Bounded use only (feed-page sized sets).
     *
     * Derived query so Spring Data Cassandra expands each ID to a separate
     * bind marker instead of binding the collection as a single LIST parameter
     * (which causes DefaultListType → SetType ClassCastException).
     */
    List<SaveLookupEntity> findAllByPostIdInAndUserId(Collection<UUID> postIds, UUID userId);

    @Query("DELETE FROM saves_by_post_user WHERE post_id = :postId AND user_id = :userId")
    void delete(@Param("postId") UUID postId, @Param("userId") UUID userId);
}
