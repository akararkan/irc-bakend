package ak.dev.irc.app.settings.notification.repository;

import ak.dev.irc.app.settings.notification.entity.UserNotificationPref;
import ak.dev.irc.app.settings.notification.enums.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserNotificationPrefRepository extends JpaRepository<UserNotificationPref, UUID> {

    List<UserNotificationPref> findByUserId(UUID userId);

    Optional<UserNotificationPref> findByUserIdAndEventTypeAndChannel(
            UUID userId, String eventType, NotificationChannel channel);
}
