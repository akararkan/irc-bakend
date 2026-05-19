package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.FeedByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface FeedByUserRepository extends CassandraRepository<FeedByUserEntity, MapId> {

    @Query("SELECT * FROM feed_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<FeedByUserEntity> firstPage(@Param("userId") UUID userId,
                                     @Param("pageSize") int pageSize);

    @Query("SELECT * FROM feed_by_user WHERE user_id = :userId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<FeedByUserEntity> nextPage(@Param("userId") UUID userId,
                                    @Param("cursor")  Instant cursor,
                                    @Param("pageSize") int pageSize);

    /**
     * Remove a single fanout row when a post is deleted. We have to know the
     * full clustering key (created_at + post_id) since Cassandra deletes
     * by primary key only.
     */
    @Query("DELETE FROM feed_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND post_id = :postId")
    void deleteFanoutRow(@Param("userId") UUID userId,
                         @Param("createdAt") Instant createdAt,
                         @Param("postId") UUID postId);
}
