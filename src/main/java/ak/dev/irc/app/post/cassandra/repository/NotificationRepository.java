package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.NotificationEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Bean name disambiguated to avoid clash with the legacy JPA NotificationRepository
// in app.user.repository (which NotificationServiceImpl + NotificationEventConsumer
// still use). Type-based @Autowired wiring still resolves either correctly.
@Repository("cassandraNotificationRepository")
public interface NotificationRepository extends CassandraRepository<NotificationEntity, MapId> {

    @Query("SELECT * FROM notifications_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<NotificationEntity> firstPage(@Param("userId") UUID userId,
                                       @Param("pageSize") int pageSize);

    @Query("SELECT * FROM notifications_by_user WHERE user_id = :userId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<NotificationEntity> nextPage(@Param("userId") UUID userId,
                                      @Param("cursor") Instant cursor,
                                      @Param("pageSize") int pageSize);

    @Query("UPDATE notifications_by_user SET is_read = true " +
           "WHERE user_id = :userId AND created_at = :createdAt AND notification_id = :notificationId")
    void markRead(@Param("userId") UUID userId,
                  @Param("createdAt") Instant createdAt,
                  @Param("notificationId") UUID notificationId);

    /** Coalesce a fresh same-group event into an existing notification row. */
    @Query("UPDATE notifications_by_user SET body = :body, last_actor_id = :lastActorId, " +
           "aggregate_count = :aggregateCount, is_read = false " +
           "WHERE user_id = :userId AND created_at = :createdAt AND notification_id = :notificationId")
    void coalesce(@Param("userId") UUID userId,
                  @Param("createdAt") Instant createdAt,
                  @Param("notificationId") UUID notificationId,
                  @Param("body") String body,
                  @Param("lastActorId") UUID lastActorId,
                  @Param("aggregateCount") Long aggregateCount);

    /** Hard-delete one notification row given the full PK from the lookup table. */
    @Query("DELETE FROM notifications_by_user " +
           "WHERE user_id = :userId AND created_at = :createdAt AND notification_id = :notificationId")
    void deleteRow(@Param("userId") UUID userId,
                   @Param("createdAt") Instant createdAt,
                   @Param("notificationId") UUID notificationId);
}
