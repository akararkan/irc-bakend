package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.ReactionByPostEntity;
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
public interface ReactionByPostRepository extends CassandraRepository<ReactionByPostEntity, MapId> {

    @Query("SELECT * FROM reactions_by_post WHERE post_id = :postId AND user_id = :userId")
    Optional<ReactionByPostEntity> find(@Param("postId") UUID postId,
                                        @Param("userId") UUID userId);

    /**
     * Bulk "which of these posts has user U reacted to?" — single driver
     * round-trip via a multi-partition IN query. Use ONLY for bounded sets
     * (feed pages of 20–50); IN over hundreds of partitions stresses the
     * coordinator and breaks token-aware routing.
     *
     * Derived query so Spring Data Cassandra expands each ID to a separate
     * bind marker instead of binding the collection as a single LIST parameter
     * (which causes DefaultListType → SetType ClassCastException).
     */
    List<ReactionByPostEntity> findAllByPostIdInAndUserId(Collection<UUID> postIds, UUID userId);

    @Query("DELETE FROM reactions_by_post WHERE post_id = :postId AND user_id = :userId")
    void delete(@Param("postId") UUID postId,
                @Param("userId") UUID userId);
}
