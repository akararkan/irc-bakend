package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.ViewByPostEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ViewByPostRepository extends CassandraRepository<ViewByPostEntity, MapId> {

    @Query("SELECT * FROM views_by_post WHERE post_id = :postId AND user_id = :userId")
    Optional<ViewByPostEntity> find(@Param("postId") UUID postId,
                                    @Param("userId") UUID userId);

    /** Partition-level delete — wipes every viewer row for a post. */
    @Query("DELETE FROM views_by_post WHERE post_id = :postId")
    void deleteAllForPost(@Param("postId") UUID postId);
}
