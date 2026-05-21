package ak.dev.irc.app.activity.cassandra.repository;

import ak.dev.irc.app.activity.cassandra.entity.UserActivityEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserActivityCassandraRepository
        extends CassandraRepository<UserActivityEntity, MapId> {

    @Query("SELECT * FROM activity_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<UserActivityEntity> firstPage(@Param("userId") UUID userId,
                                       @Param("pageSize") int pageSize);

    @Query("SELECT * FROM activity_by_user WHERE user_id = :userId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<UserActivityEntity> nextPage(@Param("userId") UUID userId,
                                      @Param("cursor") Instant cursor,
                                      @Param("pageSize") int pageSize);

    @Query("DELETE FROM activity_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND activity_id = :activityId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("activityId") UUID activityId);

    // ── Date-range scans ─────────────────────────────────────────────────
    // Cassandra clusters activity_by_user by (created_at DESC), so a
    // closed/half-open range filter is a single partition scan — no
    // ALLOW FILTERING needed.

    /** Inclusive range [from, to], newest first. {@code from}/{@code to} both required. */
    @Query("SELECT * FROM activity_by_user WHERE user_id = :userId " +
           "AND created_at >= :from AND created_at <= :to LIMIT :pageSize")
    List<UserActivityEntity> firstPageBetween(@Param("userId") UUID userId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to,
                                              @Param("pageSize") int pageSize);

    /** Cursor variant — page forward from {@code cursor} (exclusive). */
    @Query("SELECT * FROM activity_by_user WHERE user_id = :userId " +
           "AND created_at >= :from AND created_at < :cursor LIMIT :pageSize")
    List<UserActivityEntity> nextPageBetween(@Param("userId") UUID userId,
                                             @Param("from") Instant from,
                                             @Param("cursor") Instant cursor,
                                             @Param("pageSize") int pageSize);

    /** Open-ended "since {@code from}", newest first. */
    @Query("SELECT * FROM activity_by_user WHERE user_id = :userId " +
           "AND created_at >= :from LIMIT :pageSize")
    List<UserActivityEntity> firstPageSince(@Param("userId") UUID userId,
                                            @Param("from") Instant from,
                                            @Param("pageSize") int pageSize);

    /** Open-ended "up to {@code to}", newest first. */
    @Query("SELECT * FROM activity_by_user WHERE user_id = :userId " +
           "AND created_at <= :to LIMIT :pageSize")
    List<UserActivityEntity> firstPageUntil(@Param("userId") UUID userId,
                                            @Param("to") Instant to,
                                            @Param("pageSize") int pageSize);
}
