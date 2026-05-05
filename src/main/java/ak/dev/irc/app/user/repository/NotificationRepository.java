package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
        SELECT n FROM Notification n
        LEFT JOIN FETCH n.actor
        LEFT JOIN FETCH n.lastActor
        WHERE n.user.id = :userId
        ORDER BY n.createdAt DESC
        """)
    Page<Notification> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
        SELECT n FROM Notification n
        LEFT JOIN FETCH n.actor
        LEFT JOIN FETCH n.lastActor
        WHERE n.user.id = :userId
          AND n.isRead  = false
        ORDER BY n.createdAt DESC
        """)
    Page<Notification> findUnreadByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
        SELECT n FROM Notification n
        LEFT JOIN FETCH n.actor
        LEFT JOIN FETCH n.lastActor
        WHERE n.user.id = :userId
          AND n.type IN :types
        ORDER BY n.createdAt DESC
        """)
    Page<Notification> findByUserIdAndTypes(@Param("userId") UUID userId,
                                            @Param("types") Collection<NotificationType> types,
                                            Pageable pageable);

    long countByUserIdAndIsRead(UUID userId, boolean isRead);

    long countByUserIdAndTypeInAndIsRead(UUID userId,
                                         Collection<NotificationType> types,
                                         boolean isRead);

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
        UPDATE Notification n
        SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP
        WHERE n.user.id = :userId
          AND n.id IN :ids
          AND n.isRead = false
        """)
    int markManyRead(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("""
        UPDATE Notification n
        SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP
        WHERE n.user.id = :userId
          AND n.type IN :types
          AND n.isRead = false
        """)
    int markByTypesRead(@Param("userId") UUID userId,
                        @Param("types") Collection<NotificationType> types);

    @Modifying
    @Query("""
        DELETE FROM Notification n
        WHERE n.id = :id AND n.user.id = :userId
        """)
    int deleteOne(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Query("""
        DELETE FROM Notification n
        WHERE n.user.id = :userId AND n.isRead = true
        """)
    int deleteAllReadForUser(@Param("userId") UUID userId);

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

    /**
     * Finds the most recent unread notification in a coalescing group for the
     * given user — used by the consumer to decide whether to insert a new row
     * or merge with an existing one. Returns the freshest match.
     */
    @Query("""
        SELECT n FROM Notification n
        WHERE n.user.id   = :userId
          AND n.groupKey  = :groupKey
          AND n.isRead    = false
          AND n.createdAt >= :since
        ORDER BY n.createdAt DESC
        """)
    List<Notification> findRecentUnreadInGroup(@Param("userId") UUID userId,
                                                @Param("groupKey") String groupKey,
                                                @Param("since") LocalDateTime since,
                                                Pageable pageable);

    default Optional<Notification> findFreshestUnreadInGroup(UUID userId, String groupKey, LocalDateTime since) {
        var list = findRecentUnreadInGroup(userId, groupKey, since,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** Daily cleanup target: read notifications older than the cutoff. */
    @Modifying
    @Query("""
        DELETE FROM Notification n
        WHERE n.isRead = true
          AND n.readAt IS NOT NULL
          AND n.readAt < :cutoff
        """)
    int deleteReadOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
