package ak.dev.irc.app.user.service.impl;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.post.cassandra.entity.NotificationEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.realtime.NotificationReadEvent;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.service.NotificationDispatcher;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Cassandra-first notification reads.
 *
 * <p>Writes go through {@link NotificationDispatcher} → Cassandra
 * {@code notifications_by_user}. Reads here come from the same table via
 * {@link CassandraNotificationService}. The legacy JPA {@code Notification}
 * entity is kept around so the {@link NotificationDispatcher#dispatch} draft
 * object can still be assembled by callers — it is never persisted.</p>
 *
 * <p>Filtering by type / category is done in-memory after the partition scan.
 * Pages are small (≤ 50 typical), so the cost is negligible and we avoid a
 * second denormalized table per filter dimension.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    /** Soft cap on how many recent rows we scan for type/category filtering. */
    private static final int SCAN_HARD_LIMIT = 200;

    private final CassandraNotificationService cassandraNotifications;
    private final UserRepository               userRepository;
    private final NotificationDispatcher       dispatcher;
    private final ApplicationEventPublisher    eventPublisher;

    // ══════════════════════════════════════════════════════════════════════════
    //  SEND
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
        // Cassandra-side cleanup happens in CassandraNotificationService when
        // the consumer fires UserUnfollowedEvent; keep the unread badge fresh.
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
    //  LISTING (Cassandra)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(Pageable pageable) {
        return pageRows(authenticatedUserId(), pageable, /* unreadOnly */ false, /* types */ null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyUnread(Pageable pageable) {
        return pageRows(authenticatedUserId(), pageable, /* unreadOnly */ true, /* types */ null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotificationsByTypes(Collection<NotificationType> types,
                                                                Pageable pageable) {
        return pageRows(authenticatedUserId(), pageable, /* unreadOnly */ false, types);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotificationsByCategory(NotificationCategory category,
                                                                   Pageable pageable) {
        if (category == null) return getMyNotifications(pageable);
        return getMyNotificationsByTypes(category.types(), pageable);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COUNTS (Cassandra counter for the unfiltered case, partition scan otherwise)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public long countUnread() {
        UUID me = authenticatedUserId();
        Long count = cassandraNotifications.unreadCountFor(me);
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnreadByCategory(NotificationCategory category) {
        if (category == null) return countUnread();
        Set<String> typeNames = typeNamesOf(category.types());
        return cassandraNotifications.inbox(authenticatedUserId(), SCAN_HARD_LIMIT).stream()
                .filter(n -> !Boolean.TRUE.equals(n.getRead()))
                .filter(n -> typeNames.contains(n.getType()))
                .count();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MARK AS READ
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void markAllRead() {
        UUID me = authenticatedUserId();
        List<UUID> marked = new ArrayList<>();
        for (NotificationEntity n : cassandraNotifications.inbox(me, SCAN_HARD_LIMIT)) {
            if (Boolean.TRUE.equals(n.getRead())) continue;
            cassandraNotifications.markRead(n.getNotificationId());
            marked.add(n.getNotificationId());
        }
        eventPublisher.publishEvent(new NotificationReadEvent(me, marked, true, false));
        dispatcher.publishUnreadCount(me);
    }

    @Override
    public void markOneRead(UUID notificationId) {
        UUID me = authenticatedUserId();
        cassandraNotifications.markRead(notificationId);
        eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(notificationId), false, false));
        dispatcher.publishUnreadCount(me);
    }

    @Override
    public int markManyRead(Collection<UUID> ids) {
        UUID me = authenticatedUserId();
        if (ids == null || ids.isEmpty()) return 0;
        int count = 0;
        for (UUID id : ids) {
            cassandraNotifications.markRead(id);
            count++;
        }
        eventPublisher.publishEvent(new NotificationReadEvent(me, List.copyOf(ids), false, false));
        dispatcher.publishUnreadCount(me);
        return count;
    }

    @Override
    public int markCategoryRead(NotificationCategory category) {
        UUID me = authenticatedUserId();
        if (category == null) {
            markAllRead();
            return -1;
        }
        Set<String> typeNames = typeNamesOf(category.types());
        List<UUID> marked = new ArrayList<>();
        for (NotificationEntity n : cassandraNotifications.inbox(me, SCAN_HARD_LIMIT)) {
            if (Boolean.TRUE.equals(n.getRead())) continue;
            if (!typeNames.contains(n.getType())) continue;
            cassandraNotifications.markRead(n.getNotificationId());
            marked.add(n.getNotificationId());
        }
        eventPublisher.publishEvent(new NotificationReadEvent(me, marked, false, false));
        dispatcher.publishUnreadCount(me);
        return marked.size();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE — hard-removes the Cassandra row + lookup entry, fires SSE so
    //  every open tab drops the entry from its inbox view.
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void deleteOne(UUID notificationId) {
        UUID me = authenticatedUserId();
        boolean removed = cassandraNotifications.deleteById(notificationId);
        if (removed) {
            eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(notificationId), false, true));
            dispatcher.publishUnreadCount(me);
        }
    }

    @Override
    public int deleteAllRead() {
        UUID me = authenticatedUserId();
        int removed = cassandraNotifications.deleteAllReadFor(me);
        if (removed > 0) {
            eventPublisher.publishEvent(new NotificationReadEvent(me, List.of(), true, true));
            dispatcher.publishUnreadCount(me);
        }
        return removed;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Page<NotificationResponse> pageRows(UUID me,
                                                Pageable pageable,
                                                boolean unreadOnly,
                                                Collection<NotificationType> types) {
        int size  = Math.max(1, pageable.getPageSize());
        int page  = Math.max(0, pageable.getPageNumber());
        int scan  = Math.min(SCAN_HARD_LIMIT, size * (page + 1));

        List<NotificationEntity> rows = cassandraNotifications.inbox(me, scan);

        Set<String> typeNames = types == null || types.isEmpty()
                ? null
                : typeNamesOf(types);

        List<NotificationEntity> filtered = rows.stream()
                .filter(n -> !unreadOnly || !Boolean.TRUE.equals(n.getRead()))
                .filter(n -> typeNames == null || typeNames.contains(n.getType()))
                .toList();

        int from = Math.min(page * size, filtered.size());
        int to   = Math.min(from + size, filtered.size());
        List<NotificationEntity> slice = filtered.subList(from, to);

        // Bulk-load every actor referenced in this slice to avoid N+1.
        Set<UUID> actorIds = new HashSet<>();
        for (NotificationEntity n : slice) {
            if (n.getActorId() != null)     actorIds.add(n.getActorId());
            if (n.getLastActorId() != null) actorIds.add(n.getLastActorId());
        }
        Map<UUID, User> users = actorIds.isEmpty()
                ? Map.of()
                : indexById(userRepository.findAllById(actorIds));

        List<NotificationResponse> mapped = slice.stream()
                .map(n -> toResponse(n, users))
                .toList();

        return new PageImpl<>(mapped, pageable, filtered.size());
    }

    private static Map<UUID, User> indexById(Iterable<User> users) {
        Map<UUID, User> m = new HashMap<>();
        users.forEach(u -> m.put(u.getId(), u));
        return m;
    }

    private NotificationResponse toResponse(NotificationEntity n, Map<UUID, User> users) {
        NotificationType type = parseType(n.getType());
        NotificationCategory category = categoryOf(type);

        User actor     = n.getActorId()     == null ? null : users.get(n.getActorId());
        User lastActor = n.getLastActorId() == null ? null : users.get(n.getLastActorId());

        return new NotificationResponse(
                n.getNotificationId(),
                type,
                category,
                n.getTitle(),
                n.getBody(),
                n.getActorId(),
                actor == null ? null : actor.getUsername(),
                actor == null ? null : actor.getFullName(),
                actor == null ? null : actor.getProfileImage(),
                n.getAggregateCount() == null ? 1L : n.getAggregateCount(),
                n.getLastActorId(),
                lastActor == null ? null : lastActor.getUsername(),
                n.getResourceId(),
                n.getResourceType(),
                deepLinkOf(n.getResourceType(), n.getResourceId()),
                Boolean.TRUE.equals(n.getRead()),
                /* readAt */ null,
                n.getCreatedAt() == null ? null
                        : LocalDateTime.ofInstant(n.getCreatedAt(), ZoneOffset.UTC));
    }

    private static String deepLinkOf(String resourceType, UUID resourceId) {
        if (resourceId == null || resourceType == null) return null;
        return switch (resourceType) {
            case "Post"     -> "/posts/"     + resourceId;
            case "Comment"  -> "/comments/"  + resourceId;
            case "Question" -> "/questions/" + resourceId;
            case "Answer"   -> "/answers/"   + resourceId;
            case "Research" -> "/researches/"+ resourceId;
            case "User"     -> "/users/"     + resourceId;
            default         -> null;
        };
    }

    private static NotificationType parseType(String name) {
        if (name == null) return null;
        try { return NotificationType.valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** Reverse-lookup: which category contains this type? First match wins. */
    private static NotificationCategory categoryOf(NotificationType type) {
        if (type == null) return null;
        for (NotificationCategory c : NotificationCategory.values()) {
            if (c.types() != null && c.types().contains(type)) return c;
        }
        return null;
    }

    private static Set<String> typeNamesOf(Collection<NotificationType> types) {
        if (types == null || types.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>(types.size());
        for (NotificationType t : types) out.add(t.name());
        return out;
    }

    private UUID authenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException(
                        "You must be authenticated to access notifications."));
    }
}
