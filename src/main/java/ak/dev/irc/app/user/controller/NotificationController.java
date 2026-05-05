package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.realtime.NotificationSseService;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService    notificationService;
    private final NotificationSseService sseService;
    private final JwtTokenProvider       jwtTokenProvider;

    // ── Real-time SSE Stream ──────────────────────────────────────────────────

    /**
     * Establishes a Server-Sent Events stream for the authenticated user.
     *
     * <p>Browser {@code EventSource} cannot send custom headers, so the JWT
     * access token can also be passed as {@code ?token=<accessToken>}.</p>
     *
     * <p>Event types delivered on this stream:
     * <ul>
     *   <li>{@code connected}    — handshake on subscribe.</li>
     *   <li>{@code notification} — a new (or coalesced) {@link NotificationResponse}.</li>
     *   <li>{@code unread-count} — {@code {count: N}} after every state change.</li>
     *   <li>{@code read}         — {@code {ids:[...], allRead, deleted:false}} so other tabs sync.</li>
     *   <li>{@code deleted}      — {@code {ids:[...], allRead, deleted:true}} after delete actions.</li>
     *   <li>{@code heartbeat}    — keepalive every ~25 s.</li>
     * </ul>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("permitAll()")
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token) {
        UUID userId = SecurityUtils.getCurrentUserId().orElse(null);

        if (userId == null && StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)
                        && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                    userId = jwtTokenProvider.getUserIdFromToken(token);
                    log.debug("[SSE] Authenticated user [{}] via query-param token", userId);
                }
            } catch (Exception ex) {
                log.warn("[SSE] Invalid token supplied via query param: {}", ex.getMessage());
            }
        }

        if (userId == null) {
            throw new UnauthorizedException(
                    "You must be authenticated to subscribe to notifications. " +
                    "Pass your access token as ?token=<jwt> for SSE connections.");
        }

        log.info("[SSE] User [{}] opening notification stream", userId);
        return sseService.subscribe(userId);
    }

    // ── REST: listing ─────────────────────────────────────────────────────────

    /**
     * List notifications. Optional filters:
     * <ul>
     *   <li>{@code category=POSTS|QNA|RESEARCH|MENTIONS|SOCIAL|SYSTEM}</li>
     *   <li>{@code type=POST_REACTED} (repeatable)</li>
     *   <li>{@code unread=true} — only unread.</li>
     * </ul>
     */
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getAll(
            @RequestParam(required = false) NotificationCategory category,
            @RequestParam(required = false) List<NotificationType> type,
            @RequestParam(required = false) Boolean unread,
            @PageableDefault(size = 20) Pageable pageable) {

        if (Boolean.TRUE.equals(unread)) {
            return ResponseEntity.ok(notificationService.getMyUnread(pageable));
        }
        if (category != null) {
            return ResponseEntity.ok(notificationService.getMyNotificationsByCategory(category, pageable));
        }
        if (type != null && !type.isEmpty()) {
            return ResponseEntity.ok(notificationService.getMyNotificationsByTypes(type, pageable));
        }
        return ResponseEntity.ok(notificationService.getMyNotifications(pageable));
    }

    @GetMapping("/unread")
    public ResponseEntity<Page<NotificationResponse>> getUnread(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(notificationService.getMyUnread(pageable));
    }

    @GetMapping("/unread/count")
    public ResponseEntity<Map<String, Long>> countUnread(
            @RequestParam(required = false) NotificationCategory category) {
        long count = category != null
                ? notificationService.countUnreadByCategory(category)
                : notificationService.countUnread();
        return ResponseEntity.ok(Map.of("count", count));
    }

    // ── REST: mark as read ────────────────────────────────────────────────────

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markOneRead(@PathVariable UUID id) {
        notificationService.markOneRead(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Bulk mark — POST {@code {"ids": [...]}}. Returns count actually updated
     * (already-read rows are skipped).
     */
    @PatchMapping("/read")
    public ResponseEntity<Map<String, Integer>> markManyRead(@RequestBody MarkReadRequest body) {
        int updated = notificationService.markManyRead(
                body == null ? null : body.ids());
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PatchMapping("/category/{category}/read")
    public ResponseEntity<Map<String, Integer>> markCategoryRead(
            @PathVariable NotificationCategory category) {
        int updated = notificationService.markCategoryRead(category);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    // ── REST: delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOne(@PathVariable UUID id) {
        notificationService.deleteOne(id);
        return ResponseEntity.noContent().build();
    }

    /** Purge all already-read notifications for the current user. */
    @DeleteMapping("/read")
    public ResponseEntity<Map<String, Integer>> deleteAllRead() {
        int deleted = notificationService.deleteAllRead();
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    public record MarkReadRequest(List<UUID> ids) {}
}
