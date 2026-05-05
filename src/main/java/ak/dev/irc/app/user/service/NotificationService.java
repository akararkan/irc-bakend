package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

public interface NotificationService {

    void sendFollowNotification(User actor, User target);
    void removeFollowNotification(UUID actorId, UUID targetId);
    void sendUnblockNotification(User actor, User target);
    void sendSystemNotification(UUID userId, String title, String body);

    // ── Listing ────────────────────────────────────────────────────────────
    Page<NotificationResponse> getMyNotifications(Pageable pageable);
    Page<NotificationResponse> getMyUnread(Pageable pageable);
    /** Filter by an explicit set of types (empty/null = all). */
    Page<NotificationResponse> getMyNotificationsByTypes(Collection<NotificationType> types, Pageable pageable);
    /** Filter by inbox tab (POSTS, QNA, RESEARCH, MENTIONS, SOCIAL, SYSTEM). */
    Page<NotificationResponse> getMyNotificationsByCategory(NotificationCategory category, Pageable pageable);

    // ── Counts ─────────────────────────────────────────────────────────────
    long countUnread();
    long countUnreadByCategory(NotificationCategory category);

    // ── Mark as read ───────────────────────────────────────────────────────
    void markAllRead();
    void markOneRead(UUID notificationId);
    int  markManyRead(Collection<UUID> ids);
    int  markCategoryRead(NotificationCategory category);

    // ── Delete ─────────────────────────────────────────────────────────────
    void deleteOne(UUID notificationId);
    int  deleteAllRead();
}
