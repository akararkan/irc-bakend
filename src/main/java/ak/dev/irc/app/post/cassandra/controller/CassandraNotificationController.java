package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.NotificationEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class CassandraNotificationController {

    private final CassandraNotificationService service;

    @GetMapping
    public List<NotificationEntity> inbox(@RequestParam UUID userId,
                                          @RequestParam(defaultValue = "20") int pageSize,
                                          @RequestParam(required = false) Instant cursor) {
        return cursor == null
                ? service.inbox(userId, pageSize)
                : service.inboxAfter(userId, cursor, pageSize);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unread(@RequestParam UUID userId) {
        return Map.of("userId", userId, "unread", service.unreadCountFor(userId));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID notificationId) {
        service.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    /** Direct dispatch — used by tests / admin tools to deliver a notification. */
    @PostMapping("/deliver")
    public Map<String, Object> deliver(@RequestBody CassandraNotificationService.DeliverRequest req) {
        return service.deliverSync(req)
                .map(id -> Map.<String, Object>of("notificationId", id))
                .orElseGet(() -> Map.<String, Object>of("suppressed", true));
    }
}
