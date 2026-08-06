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

    /** Bounded single-partition counts — the break-glass histogram source. */
    @Query("SELECT COUNT(*) FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type")
    long countFor(@Param("userId") UUID userId, @Param("type") String activityType);

    @Query("SELECT COUNT(*) FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type AND created_at >= :from")
    long countForSince(@Param("userId") UUID userId, @Param("type") String activityType,
                       @Param("from") Instant from);

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

    // ── Date-range scans for the type-filtered view ──────────────────────

    @Query("SELECT * FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type " +
           "AND created_at >= :from AND created_at <= :to LIMIT :pageSize")
    List<UserActivityByTypeEntity> firstPageBetween(@Param("userId") UUID userId,
                                                    @Param("type") String activityType,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to,
                                                    @Param("pageSize") int pageSize);

    @Query("SELECT * FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type " +
           "AND created_at >= :from AND created_at < :cursor LIMIT :pageSize")
    List<UserActivityByTypeEntity> nextPageBetween(@Param("userId") UUID userId,
                                                   @Param("type") String activityType,
                                                   @Param("from") Instant from,
                                                   @Param("cursor") Instant cursor,
                                                   @Param("pageSize") int pageSize);

    @Query("SELECT * FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type AND created_at >= :from LIMIT :pageSize")
    List<UserActivityByTypeEntity> firstPageSince(@Param("userId") UUID userId,
                                                  @Param("type") String activityType,
                                                  @Param("from") Instant from,
                                                  @Param("pageSize") int pageSize);

    @Query("SELECT * FROM activity_by_user_and_type WHERE user_id = :userId " +
           "AND activity_type = :type AND created_at <= :to LIMIT :pageSize")
    List<UserActivityByTypeEntity> firstPageUntil(@Param("userId") UUID userId,
                                                  @Param("type") String activityType,
                                                  @Param("to") Instant to,
                                                  @Param("pageSize") int pageSize);
}
