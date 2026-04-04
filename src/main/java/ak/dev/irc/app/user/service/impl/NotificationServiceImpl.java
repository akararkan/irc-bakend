package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.NotificationRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.mapper.NotificationMapper;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifRepository;
    private final UserRepository userRepository;
    private final NotificationMapper     notifMapper;

    // ══════════════════════════════════════════════════════════════════════════
    //  SEND NOTIFICATIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void sendFollowNotification(User actor, User target) {
        log.info("Sending FOLLOW notification — actor=[{}] ({}), target=[{}] ({})",
                actor.getId(), actor.getUsername(), target.getId(), target.getUsername());

        Notification n = Notification.builder()
                .user(target)
                .actor(actor)
                .type(NotificationType.NEW_FOLLOWER)
                .title("New follower")
                .body(actor.getFullName() + " (@" + actor.getUsername() + ") started following you.")
                .build();
        notifRepository.save(n);

        log.debug("Follow notification [{}] saved for target [{}]", n.getId(), target.getId());
    }

    @Override
    public void removeFollowNotification(UUID actorId, UUID targetId) {
        log.info("Removing FOLLOW notification — actor=[{}], target=[{}]", actorId, targetId);

        notifRepository.deleteByUserActorAndType(
                targetId, actorId, NotificationType.NEW_FOLLOWER);

        log.debug("Follow notification cleaned up for actor=[{}], target=[{}]", actorId, targetId);
    }

    @Override
    public void sendUnblockNotification(User actor, User target) {
        log.info("Sending UNBLOCK notification — actor=[{}] ({}), target=[{}] ({})",
                actor.getId(), actor.getUsername(), target.getId(), target.getUsername());

        Notification n = Notification.builder()
                .user(target)
                .actor(actor)
                .type(NotificationType.UNBLOCKED)
                .title("You have been unblocked")
                .body(actor.getFullName() + " (@" + actor.getUsername() + ") unblocked you.")
                .build();
        notifRepository.save(n);

        log.debug("Unblock notification [{}] saved for target [{}]", n.getId(), target.getId());
    }

    @Override
    public void sendSystemNotification(UUID userId, String title, String body) {
        log.info("Sending SYSTEM notification — user=[{}], title='{}'", userId, title);

        User user = userRepository.findActiveById(userId)
                .orElseThrow(() -> {
                    log.warn("Cannot send system notification — user [{}] not found", userId);
                    return new ResourceNotFoundException("User", "id", userId);
                });

        Notification n = Notification.builder()
                .user(user)
                .type(NotificationType.SYSTEM_MESSAGE)
                .title(title)
                .body(body)
                .build();
        notifRepository.save(n);

        log.info("System notification [{}] sent to user [{}]", n.getId(), userId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ NOTIFICATIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(Pageable pageable) {
        UUID me = authenticatedUserId();
        log.debug("Fetching all notifications for user [{}] — page={}, size={}",
                me, pageable.getPageNumber(), pageable.getPageSize());

        Page<NotificationResponse> result = notifRepository.findByUserId(me, pageable)
                .map(notifMapper::toResponse);

        log.debug("User [{}] has {} total notification(s)", me, result.getTotalElements());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyUnread(Pageable pageable) {
        UUID me = authenticatedUserId();
        log.debug("Fetching unread notifications for user [{}]", me);

        Page<NotificationResponse> result = notifRepository.findUnreadByUserId(me, pageable)
                .map(notifMapper::toResponse);

        log.debug("User [{}] has {} unread notification(s)", me, result.getTotalElements());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread() {
        UUID me = authenticatedUserId();
        long count = notifRepository.countByUserIdAndIsRead(me, false);
        log.debug("User [{}] unread count = {}", me, count);
        return count;
    }

    @Override
    public void markAllRead() {
        UUID me = authenticatedUserId();
        log.info("User [{}] marking all notifications as read", me);
        notifRepository.markAllReadForUser(me);
        log.debug("All notifications marked read for user [{}]", me);
    }

    @Override
    public void markOneRead(UUID notificationId) {
        UUID me = authenticatedUserId();
        log.info("User [{}] marking notification [{}] as read", me, notificationId);
        notifRepository.markOneRead(notificationId, me);
        log.debug("Notification [{}] marked read for user [{}]", notificationId, me);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPER
    // ══════════════════════════════════════════════════════════════════════════

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> {
                    log.warn("Unauthenticated access attempt in notification service");
                    return new UnauthorizedException(
                            "You must be authenticated to access notifications.");
                });
    }
}
