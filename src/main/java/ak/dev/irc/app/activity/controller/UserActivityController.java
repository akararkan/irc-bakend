package ak.dev.irc.app.activity.controller;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeService;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/activity")
@RequiredArgsConstructor
public class UserActivityController {

    private final UserActivityService activityService;
    private final UserActivityRealtimeService realtimeService;

    @GetMapping
    public ResponseEntity<Page<UserActivityResponse>> listMyActivity(
            @RequestParam(value = "type", required = false) UserActivityType type,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(activityService.listMyActivity(user.getId(), type, pageable));
    }

    @DeleteMapping("/{activityId}")
    public ResponseEntity<Void> deleteOne(
            @PathVariable UUID activityId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        activityService.deleteOne(user.getId(), activityId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteAll(
            @RequestParam(value = "type", required = false) UserActivityType type,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        int deleted = activityService.deleteAll(user.getId(), type);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Live-stream this user's activity to the client. Every search, mention
     * lookup, reaction, comment, share and reel-watch is pushed as an SSE
     * event the moment its database row is committed — across every running
     * application instance via the Redis pub/sub fan-out.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal User user) {
        if (user == null) {
            SseEmitter unauthorized = new SseEmitter(0L);
            unauthorized.completeWithError(new IllegalStateException("Unauthorized"));
            return unauthorized;
        }
        return realtimeService.subscribe(user.getId());
    }
}
