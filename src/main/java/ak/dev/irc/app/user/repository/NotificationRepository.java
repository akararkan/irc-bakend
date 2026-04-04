package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
        SELECT n FROM Notification n
        LEFT JOIN FETCH n.actor
        WHERE n.user.id = :userId
        ORDER BY n.createdAt DESC
        """)
    Page<Notification> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
        SELECT n FROM Notification n
        LEFT JOIN FETCH n.actor
        WHERE n.user.id = :userId
          AND n.isRead  = false
        ORDER BY n.createdAt DESC
        """)
    Page<Notification> findUnreadByUserId(@Param("userId") UUID userId, Pageable pageable);

    long countByUserIdAndIsRead(UUID userId, boolean isRead);

    @Modifying
    @Query("""
        UPDATE Notification n
        SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP
        WHERE n.user.id = :userId AND n.isRead = false
        """)
    void markAllReadForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("""
        UPDATE Notification n
        SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP
        WHERE n.id = :id AND n.user.id = :userId
        """)
    void markOneRead(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Query("""
        DELETE FROM Notification n
        WHERE n.user.id  = :userId
          AND n.actor.id = :actorId
          AND n.type     = :type
        """)
    void deleteByUserActorAndType(@Param("userId")  UUID userId,
                                  @Param("actorId")  UUID actorId,
                                  @Param("type")     NotificationType type);
}
