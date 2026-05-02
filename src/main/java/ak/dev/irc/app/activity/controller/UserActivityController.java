package ak.dev.irc.app.activity.controller;

import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/activity")
@RequiredArgsConstructor
public class UserActivityController {

    private final UserActivityService activityService;

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
}
