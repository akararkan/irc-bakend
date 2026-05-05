package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.mapper.NotificationMapper;
import ak.dev.irc.app.user.realtime.NotificationReadEvent;
import ak.dev.irc.app.user.repository.NotificationRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.NotificationDispatcher;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository  notifRepository;
    private final UserRepository           userRepository;
    private final NotificationMapper       notifMapper;
    private final NotificationDispatcher   dispatcher;
    private final ApplicationEventPublisher eventPublisher;

    // ══════════════════════════════════════════════════════════════════════════
    //  SEND NOTIFICATIONS (programmatic — most events flow through the consumer)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void sendFollowNotification(User actor, User target) {
        log.info("Sending FOLLOW notification — actor=[{}], target=[{}]",
                actor.getId(), target.getId());
        dispatcher.dispatch(Notification.builder()
                .user(target)
                .actor(actor)
                .type(NotificationType.NEW_FOLLOWER)
                .title("New follower")
                .body(actor.getFullName() + " (@" + actor.getUsername() + ") started following you.")
                .resourceId(actor.getId())
                .resourceType("User")
                .build());
    }

    @Override
    public void removeFollowNotification(UUID actorId, UUID targetId) {
        notifRepository.deleteByUserActorAndType(
                targetId, actorId, NotificationType.NEW_FOLLOWER);
        // Removal can change the unread count — keep the badge accurate.
        dispatcher.publishUnreadCount(targetId);
    }

    @Override
    public void sendUnblockNotification(User actor, User target) {
        dispatcher.dispatch(Notification.builder()
                .user(target)
                .actor(actor)
                .type(NotificationType.UNBLOCKED)
                .title("You have been unblocked")
                .body(actor.getFullName() + " (@" + actor.getUsername() + ") unblocked you.")
                .resourceId(actor.getId())
                .resourceType("User")
                .build());
    }

    @Override
    public void sendSystemNotification(UUID userId, String title, String body) {
        User user = userRepository.findActiveById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        dispatcher.dispatch(Notification.builder()
                .user(user)
                .type(NotificationType.SYSTEM_MESSAGE)
                .title(title)
                .body(body)
                .build());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LISTING
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(Pageable pageable) {
        UUID me = authenticatedUserId();
        return notifRepository.findByUserId(me, pageable).map(notifMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyUnread(Pageable pageable) {
        UUID me = authenticatedUserId();
        return notifRepository.findUnreadByUserId(me, pageable).map(notifMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotificationsByTypes(Collection<NotificationType> types,
                                                                Pageable pageable) {
        UUID me = authenticatedUserId();
        if (types == null || types.isEmpty()) {
            return notifRepository.findByUserId(me, pageable).map(notifMapper::toResponse);
        }
        return notifRepository.findByUserIdAndTypes(me, types, pageable)
                .map(notifMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotificationsByCategory(NotificationCategory category,
                                                                   Pageable pageable) {
        if (category == null) return getMyNotifications(pageable);
        return getMyNotificationsByTypes(category.types(), pageable);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COUNTS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public long countUnread() {
        UUID me = authenticatedUserId();
        return notifRepository.countByUserIdAndIsRead(me, false);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnreadByCategory(NotificationCategory category) {
        UUID me = authenticatedUserId();
        if (category == null) return notifRepository.countByUserIdAndIsRead(me, false);
        return notifRepository.countByUserIdAndTypeInAndIsRead(me, category.types(), false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MARK AS READ — also fans out a `read` SSE event so sibling tabs sync.
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void markAllRead() {
        UUID me = authenticatedUserId();
        notifRepository.markAllReadForUser(me);
        eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(), true, false));
        dispatcher.publishUnreadCount(me);
    }

    @Override
    public void markOneRead(UUID notificationId) {
        UUID me = authenticatedUserId();
        notifRepository.markOneRead(notificationId, me);
        eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(notificationId), false, false));
        dispatcher.publishUnreadCount(me);
    }

    @Override
    public int markManyRead(Collection<UUID> ids) {
        UUID me = authenticatedUserId();
        if (ids == null || ids.isEmpty()) return 0;
        int updated = notifRepository.markManyRead(me, ids);
        eventPublisher.publishEvent(new NotificationReadEvent(me, List.copyOf(ids), false, false));
        dispatcher.publishUnreadCount(me);
        return updated;
    }

    @Override
    public int markCategoryRead(NotificationCategory category) {
        UUID me = authenticatedUserId();
        if (category == null) {
            markAllRead();
            return -1;
        }
        Set<NotificationType> types = category.types();
        int updated = notifRepository.markByTypesRead(me, types);
        eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(), false, false));
        dispatcher.publishUnreadCount(me);
        return updated;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void deleteOne(UUID notificationId) {
        UUID me = authenticatedUserId();
        int deleted = notifRepository.deleteOne(notificationId, me);
        if (deleted > 0) {
            eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(notificationId), false, true));
            dispatcher.publishUnreadCount(me);
        }
    }

    @Override
    public int deleteAllRead() {
        UUID me = authenticatedUserId();
        int deleted = notifRepository.deleteAllReadForUser(me);
        if (deleted > 0) {
            eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(), true, true));
            dispatcher.publishUnreadCount(me);
        }
        return deleted;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPER
    // ══════════════════════════════════════════════════════════════════════════

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException(
                        "You must be authenticated to access notifications."));
    }
}
