package ak.dev.irc.app.activity.controller;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeService;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.entity.User;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/users/me/activity")
@RequiredArgsConstructor
public class UserActivityController {

    private final UserActivityService activityService;
    private final UserActivityRealtimeService realtimeService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Paginated user-activity history.
     *
     * <h4>Query parameters (all optional)</h4>
     * <ul>
     *   <li>{@code type}    — single activity type. Kept for back-compat;
     *                         prefer {@code types} for new clients.</li>
     *   <li>{@code types}   — repeatable / comma-separated list of activity
     *                         types to include (union / OR). When supplied,
     *                         {@code type} is ignored.</li>
     *   <li>{@code from}    — ISO-8601 instant. Inclusive lower bound on
     *                         {@code createdAt} (e.g.
     *                         {@code 2026-05-01T00:00:00Z}).</li>
     *   <li>{@code to}      — ISO-8601 instant. Inclusive upper bound.</li>
     *   <li>{@code page}, {@code size} — standard Spring pagination.</li>
     * </ul>
     *
     * <h4>Examples</h4>
     * <pre>
     *   GET /api/v1/users/me/activity                                  → everything, newest first
     *   GET /api/v1/users/me/activity?type=POST_COMMENT                → just my post comments
     *   GET /api/v1/users/me/activity?types=POST_REACTION,POST_COMMENT → both, unioned
     *   GET /api/v1/users/me/activity?from=2026-05-01T00:00:00Z        → since May 1
     *   GET /api/v1/users/me/activity?from=2026-05-01T00:00:00Z&amp;to=2026-05-31T23:59:59Z
     *                                                                 → May only
     *   GET /api/v1/users/me/activity?types=REEL_WATCH,USER_MENTIONED&amp;from=...&amp;to=...
     * </pre>
     */
    @GetMapping
    public ResponseEntity<Page<UserActivityResponse>> listMyActivity(
            @RequestParam(value = "type",  required = false) UserActivityType type,
            @RequestParam(value = "types", required = false) List<UserActivityType> types,
            @RequestParam(value = "from",  required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to",    required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");

        // `types` takes precedence over the legacy single `type` param.
        List<UserActivityType> resolved = (types != null && !types.isEmpty())
                ? types
                : (type != null ? List.of(type) : null);

        return ResponseEntity.ok(
                activityService.listMyActivity(user.getId(), resolved, from, to, pageable));
    }

    @DeleteMapping("/{activityId}")
    public ResponseEntity<Void> deleteOne(
            @PathVariable UUID activityId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        activityService.deleteOne(user.getId(), activityId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteAll(
            @RequestParam(value = "type", required = false) UserActivityType type,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        int deleted = activityService.deleteAll(user.getId(), type);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Live-stream this user's activity to the client. Every search, mention
     * lookup, reaction, comment, share and reel-watch is pushed as an SSE
     * event the moment its database row is committed — across every running
     * application instance via the Redis pub/sub fan-out.
     *
     * <p>Auth: the JWT principal is preferred. Browser {@code EventSource}
     * cannot send custom headers, so the access token can also be passed as
     * {@code ?token=<accessToken>}. Mirrors {@code NotificationController.stream}.</p>
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token,
                             @AuthenticationPrincipal User user,
                             HttpServletResponse response) {

        UUID userId = user != null ? user.getId() : null;

        // Fallback: pull userId from the query-param token when there's no
        // principal (EventSource can't set Authorization headers).
        if (userId == null && StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)
                        && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                    userId = jwtTokenProvider.getUserIdFromToken(token);
                    log.debug("[ACTIVITY-SSE] Authenticated user [{}] via query-param token", userId);
                }
            } catch (Exception ex) {
                log.warn("[ACTIVITY-SSE] Invalid token supplied via query param: {}", ex.getMessage());
            }
        }

        // Auth failure: write a 401 directly. Throwing here would funnel the
        // exception through GlobalExceptionHandler which would try to render
        // application/json into a response already negotiated as
        // text/event-stream → HttpMediaTypeNotAcceptable → null response in
        // the browser (which Firefox surfaces as "CORS error / status null").
        if (userId == null) {
            try {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write(
                        "Authentication required. Pass access token as ?token=<jwt>.");
                response.flushBuffer();
            } catch (Exception ignored) { /* connection going to close anyway */ }
            return null;
        }

        // Disable proxy buffering (Railway/Nginx/Cloudflare) so events stream
        // immediately instead of being held until the response closes.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Connection", "keep-alive");

        log.info("[ACTIVITY-SSE] User [{}] opening activity stream", userId);
        return realtimeService.subscribe(userId);
    }
}
