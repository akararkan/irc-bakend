package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.SaveByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SaveByUserRepository extends CassandraRepository<SaveByUserEntity, MapId> {

    @Query("SELECT * FROM saves_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<SaveByUserEntity> firstPage(@Param("userId") UUID userId,
                                     @Param("pageSize") int pageSize);

    @Query("SELECT * FROM saves_by_user WHERE user_id = :userId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<SaveByUserEntity> nextPage(@Param("userId") UUID userId,
                                    @Param("cursor") Instant cursor,
                                    @Param("pageSize") int pageSize);

    /**
     * Remove one save row given its full clustering key. The createdAt comes
     * from the lookup table (saves_by_post_user) — caller must supply it.
     */
    @Query("DELETE FROM saves_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND post_id = :postId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("postId") UUID postId);
}
