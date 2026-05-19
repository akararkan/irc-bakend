package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.ReactionByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReactionByUserRepository extends CassandraRepository<ReactionByUserEntity, MapId> {

    @Query("SELECT * FROM reactions_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<ReactionByUserEntity> recent(@Param("userId") UUID userId,
                                      @Param("pageSize") int pageSize);

    /**
     * Remove a specific reaction from the user's history. Caller must supply
     * the original createdAt (read from reactions_by_post) because the
     * clustering key includes it — Cassandra deletes by full primary key only.
     */
    @Query("DELETE FROM reactions_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND post_id = :postId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("postId") UUID postId);
}
