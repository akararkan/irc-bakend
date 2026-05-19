package ak.dev.irc.app.activity.cassandra.repository;

import ak.dev.irc.app.activity.cassandra.entity.ReelViewEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReelViewCassandraRepository
        extends CassandraRepository<ReelViewEntity, MapId> {

    @Query("SELECT * FROM reel_views_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<ReelViewEntity> firstPage(@Param("userId") UUID userId,
                                   @Param("pageSize") int pageSize);

    @Query("SELECT * FROM reel_views_by_user WHERE user_id = :userId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<ReelViewEntity> nextPage(@Param("userId") UUID userId,
                                  @Param("cursor") Instant cursor,
                                  @Param("pageSize") int pageSize);

    @Query("SELECT * FROM reel_views_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND reel_view_id = :reelViewId")
    Optional<ReelViewEntity> find(@Param("userId") UUID userId,
                                  @Param("createdAt") Instant createdAt,
                                  @Param("reelViewId") UUID reelViewId);

    @Query("DELETE FROM reel_views_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND reel_view_id = :reelViewId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("reelViewId") UUID reelViewId);
}
