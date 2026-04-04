package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    void sendFollowNotification(User actor, User target);
    void removeFollowNotification(UUID actorId, UUID targetId);
    void sendUnblockNotification(User actor, User target);
    void sendSystemNotification(UUID userId, String title, String body);

    Page<NotificationResponse> getMyNotifications(Pageable pageable);
    Page<NotificationResponse> getMyUnread(Pageable pageable);
    long                       countUnread();
    void                       markAllRead();
    void                       markOneRead(UUID notificationId);
}
