package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.dto.response.NotificationResponse;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    // ── Real-time SSE Stream ──────────────────────────────────────────────────

    /**
     * Establishes a Server-Sent Events stream for the authenticated user.
     *
     * <p>The client receives three event types:
     * <ul>
     *   <li>{@code connected}   — sent immediately on connect (handshake).</li>
     *   <li>{@code notification} — a new {@link NotificationResponse} JSON object.</li>
     *   <li>{@code heartbeat}   — a lightweight ping every ~25 s to keep the
     *       connection alive through load balancers and proxies.</li>
     * </ul>
     * </p>
     *
     * <p><strong>Client usage (JavaScript):</strong>
     * <pre>{@code
     * const evtSource = new EventSource('/api/v1/notifications/stream', { withCredentials: true });
     * evtSource.addEventListener('notification', e => {
     *     const notification = JSON.parse(e.data);
     *     // update UI
     * });
     * evtSource.addEventListener('heartbeat', () => { // keep-alive — no-op });
     * evtSource.onerror = () => { evtSource.close(); /* reconnect logic &#42;/ };
     * }</pre>
     * </p>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        UUID userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException(
                        "You must be authenticated to subscribe to notifications."));
        log.info("[SSE] User [{}] opening notification stream", userId);
        return sseService.subscribe(userId);
    }

    // ── REST polling endpoints (complement to SSE) ────────────────────────────

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getAll(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(notificationService.getMyNotifications(pageable));
    }

    @GetMapping("/unread")
    public ResponseEntity<Page<NotificationResponse>> getUnread(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(notificationService.getMyUnread(pageable));
    }

    @GetMapping("/unread/count")
    public ResponseEntity<Map<String, Long>> countUnread() {
        return ResponseEntity.ok(Map.of("count", notificationService.countUnread()));
    }

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
}
