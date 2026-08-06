package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.common.messages.UserMessages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;
import ak.dev.irc.app.user.realtime.NotificationSseService;
import ak.dev.irc.app.user.service.NotificationService;
import jakarta.servlet.http.HttpServletResponse;
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
     *   <li>{@code heartbeat}    — keepalive every 15 s.</li>
     * </ul>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("permitAll()")
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token,
                             HttpServletResponse response) {
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

        // Auth failure path: throwing an exception here causes Spring's
        // GlobalExceptionHandler to try writing a JSON body into a response
        // already negotiated as text/event-stream → HttpMediaTypeNotAcceptable
        // → 500 with empty body. Write the 401 status directly instead and
        // return null so Spring closes the connection cleanly.
        if (userId == null) {
            try {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write(UserMessages.NOTE_SSE_AUTH_REQUIRED);
                response.flushBuffer();
            } catch (Exception ignored) { /* swallowed — connection is going to close anyway */ }
            return null;
        }

        // Disable proxy buffering (Railway/Nginx/Cloudflare) so events stream
        // immediately instead of being held until the response closes.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Connection", "keep-alive");

        log.info("[SSE] User [{}] opening notification stream", userId);
        return sseService.subscribe(userId);
    }

    // ── REST: listing ─────────────────────────────────────────────────────────

    /**
     * List notifications. All filters compose (AND) — {@code unread} no longer
     * shadows {@code category}/{@code type} (N3):
     * <ul>
     *   <li>{@code category=POSTS|QNA|RESEARCH|MENTIONS|SOCIAL|SYSTEM}</li>
     *   <li>{@code type=POST_REACTED} (repeatable)</li>
     *   <li>{@code unread=true} — restrict to unread.</li>
     * </ul>
     * e.g. {@code ?unread=true&category=QNA} returns only unread Q&A items.
     * When both {@code category} and {@code type} are supplied, {@code category}
     * wins (it already names a fixed type set).
     */
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getAll(
            @RequestParam(required = false) NotificationCategory category,
            @RequestParam(required = false) List<NotificationType> type,
            @RequestParam(required = false) Boolean unread,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(notificationService.getMyNotifications(
                Boolean.TRUE.equals(unread), category, type, pageable));
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
