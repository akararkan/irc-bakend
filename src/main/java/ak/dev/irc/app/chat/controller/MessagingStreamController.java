package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.TypingRequest;
import ak.dev.irc.app.chat.dto.response.PresenceResponse;
import ak.dev.irc.app.chat.realtime.ChatSseService;
import ak.dev.irc.app.chat.service.PresenceService;
import ak.dev.irc.app.chat.service.TypingService;
import ak.dev.irc.app.chat.service.UnreadBadgeCache;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.user.entity.User;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single per-user chat SSE stream plus the ephemeral signals that ride
 * alongside it: typing, presence, and the unread badge. One stream carries every
 * chat event for the user (new messages, edits, deletes, reactions, receipts,
 * typing, presence, group events), multiplexed by {@code event:} name.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MessagingStreamController {

    private final ChatSseService sseService;
    private final TypingService typingService;
    private final PresenceService presenceService;
    private final UnreadBadgeCache unreadBadge;
    private final JwtTokenProvider jwtTokenProvider;

    /** The one SSE stream for all of the caller's conversations. */
    @GetMapping(value = "/messaging/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("permitAll()")
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token,
                             HttpServletResponse response) {
        UUID userId = SecurityUtils.getCurrentUserId().orElse(null);
        if (userId == null && StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)
                        && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                    userId = jwtTokenProvider.getUserIdFromToken(token);
                }
            } catch (Exception ex) {
                log.warn("[CHAT-SSE] invalid token via query param: {}", ex.getMessage());
            }
        }
        if (userId == null) {
            try {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write("Authentication required. Pass access token as ?token=<jwt>.");
                response.flushBuffer();
            } catch (Exception ignored) { /* connection closing anyway */ }
            return null;
        }
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Connection", "keep-alive");
        return sseService.subscribe(userId);
    }

    /** Ephemeral typing indicator to the other members of a conversation. */
    @PostMapping("/conversations/{id}/typing")
    public ResponseEntity<Void> typing(@PathVariable UUID id,
                                       @RequestBody TypingRequest req,
                                       @AuthenticationPrincipal User user) {
        typingService.handleTyping(id, requireId(user), req.isTyping());
        return ResponseEntity.ok().build();
    }

    /** Batch presence lookup for a set of users (e.g. a conversation's members). */
    @GetMapping("/presence")
    public ResponseEntity<List<PresenceResponse>> presence(@RequestParam List<UUID> userIds,
                                                           @AuthenticationPrincipal User user) {
        UUID me = requireId(user);
        return ResponseEntity.ok(presenceService.presenceOf(userIds, me));
    }

    /** Total unread badge across all my conversations. */
    @GetMapping("/messaging/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("count", unreadBadge.total(requireId(user))));
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
