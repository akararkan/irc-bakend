package ak.dev.irc.app.activity.cassandra.repository;

import ak.dev.irc.app.activity.cassandra.entity.UserActivityByTypeEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserActivityByTypeRepository
        extends CassandraRepository<UserActivityByTypeEntity, MapId> {

    @Query("SELECT * FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type LIMIT :pageSize")
    List<UserActivityByTypeEntity> firstPage(@Param("userId") UUID userId,
                                             @Param("type") String activityType,
                                             @Param("pageSize") int pageSize);

    @Query("SELECT * FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type AND created_at < :cursor LIMIT :pageSize")
    List<UserActivityByTypeEntity> nextPage(@Param("userId") UUID userId,
                                            @Param("type") String activityType,
                                            @Param("cursor") Instant cursor,
                                            @Param("pageSize") int pageSize);

    @Query("DELETE FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type AND created_at = :createdAt AND activity_id = :activityId")
    void delete(@Param("userId") UUID userId,
                @Param("type") String activityType,
                @Param("createdAt") Instant createdAt,
                @Param("activityId") UUID activityId);
}
