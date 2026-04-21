package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
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
import org.springframework.util.StringUtils;
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
    private final JwtTokenProvider       jwtTokenProvider;

    // ── Real-time SSE Stream ──────────────────────────────────────────────────

    /**
     * Establishes a Server-Sent Events stream for the authenticated user.
     *
     * <p>Because the browser {@code EventSource} API does <strong>not</strong>
     * support custom HTTP headers, this endpoint also accepts the JWT access
     * token as a query parameter: {@code ?token=<accessToken>}.</p>
     *
     * <p>Authentication resolution order:
     * <ol>
     *   <li>SecurityContext (populated by JwtAuthenticationFilter if header/cookie present)</li>
     *   <li>Query parameter {@code token} (fallback for EventSource clients)</li>
     * </ol>
     *
     * <p>The client receives three event types:
     * <ul>
     *   <li>{@code connected}    — sent immediately on connect (handshake).</li>
     *   <li>{@code notification} — a new {@link NotificationResponse} JSON object.</li>
     *   <li>{@code heartbeat}    — a lightweight ping every ~25 s to keep the
     *       connection alive through load balancers and proxies.</li>
     * </ul>
     *
     * <p><strong>Client usage (JavaScript):</strong>
     * <pre>{@code
     * const token = localStorage.getItem('accessToken');
     * const url   = `/api/v1/notifications/stream?token=${token}`;
     * const evtSource = new EventSource(url, { withCredentials: true });
     * evtSource.addEventListener('notification', e => {
     *     const notification = JSON.parse(e.data);
     *     // update UI
     * });
     * evtSource.addEventListener('heartbeat', () => { // keep-alive });
     * evtSource.onerror = () => { evtSource.close(); // reconnect };
     * }</pre>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("permitAll()")                 // ← override class-level @PreAuthorize; we do manual auth below
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token) {

        // 1) Try SecurityContext first (normal flow when JwtAuthenticationFilter is active)
        UUID userId = SecurityUtils.getCurrentUserId().orElse(null);

        // 2) Fallback: resolve from query-param token (EventSource can't send headers)
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
