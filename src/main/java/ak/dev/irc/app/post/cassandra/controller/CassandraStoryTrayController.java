package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.common.messages.PostMessages;
import ak.dev.irc.app.post.realtime.StoryTrayRealtimeService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * HTTP binding for the story-tray Server-Sent Events stream.
 *
 * <p>The streaming infrastructure ({@link StoryTrayRealtimeService},
 * {@code StoryTrayRealtimePublisher}, {@code StoryTrayRealtimeSubscriber})
 * existed for a while as the per-viewer SSE channel for
 * {@code irc:story-tray:{viewerId}} events — but the HTTP endpoint that lets
 * a client actually open the stream was missing. The
 * {@code StoryTrayRealtimeService} javadoc claimed
 * {@code GET /api/v1/stories/tray/stream} but no controller bound it. This
 * controller closes that gap.</p>
 *
 * <h3>Event types delivered</h3>
 * <p>Event NAMES on the wire are the lowercased enum values — clients register
 * an {@code addEventListener} per name:</p>
 * <ul>
 *   <li>{@code connected}      — handshake on subscribe (sent by the service).</li>
 *   <li>{@code new_story}      — a followed/close-friend just posted; light the ring.</li>
 *   <li>{@code story_removed}  — author deleted / all stories expired; grey the ring.</li>
 *   <li>{@code poll_vote_cast} — <em>author-only</em> live poll tally update.
 *       Payload carries {@code pollId, voteA, voteB, voteTotal}; other
 *       tray fields are null. Use to update the StoryEditor's results
 *       pane without polling {@code /polls/{pollId}/results}.</li>
 *   <li>{@code heartbeat}      — keepalive every 25 s.</li>
 * </ul>
 *
 * <h3>Auth</h3>
 * <p>Browser {@code EventSource} can't send custom headers, so the JWT access
 * token is accepted as {@code ?token=<accessToken>} alongside the standard
 * bearer-header flow (same convention as
 * {@code GET /api/v1/notifications/stream}).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/stories/tray")
@RequiredArgsConstructor
public class CassandraStoryTrayController {

    private final StoryTrayRealtimeService trayService;
    private final JwtTokenProvider         jwtTokenProvider;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("permitAll()")
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token,
                             HttpServletResponse response) {
        UUID viewerId = SecurityUtils.getCurrentUserId().orElse(null);

        // EventSource can't set the Authorization header — fall back to ?token=
        if (viewerId == null && StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)
                        && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                    viewerId = jwtTokenProvider.getUserIdFromToken(token);
                    log.debug("[TRAY-SSE] Authenticated user [{}] via query-param token", viewerId);
                }
            } catch (Exception ex) {
                log.warn("[TRAY-SSE] Invalid token supplied via query param: {}", ex.getMessage());
            }
        }

        if (viewerId == null) {
            // Writing 401 plain-text instead of throwing — see NotificationController
            // for the rationale (avoids HttpMediaTypeNotAcceptable → 500 cascade
            // when the response is already negotiated as text/event-stream).
            try {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write(
                        PostMessages.AUTH_REQUIRED_SSE_MSG);
                response.flushBuffer();
            } catch (Exception ignored) { /* connection closing anyway */ }
            return null;
        }

        // Disable proxy buffering (Railway / Nginx / Cloudflare) so events
        // stream immediately instead of being held until the response closes.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Connection", "keep-alive");

        log.info("[TRAY-SSE] User [{}] opening story-tray stream", viewerId);
        return trayService.subscribe(viewerId);
    }
}
