package ak.dev.irc.app.settings.notification.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.notification.dto.NotificationSettingsDtos.*;
import ak.dev.irc.app.settings.notification.service.NotificationSettingsService;
import ak.dev.irc.app.settings.notification.service.PushTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification settings surface (spec §8): the per-event×channel preference
 * matrix, the Do-Not-Disturb window, and push-token registration.
 */
@RestController
@RequestMapping("/api/v1/settings/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationSettingsController {

    private final NotificationSettingsService service;
    private final PushTokenService pushTokenService;

    // ── Matrix ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Map<String, Boolean>>> matrix() {
        return ResponseEntity.ok(service.matrix(SecurityUtils.requireCurrentUserId()));
    }

    @PutMapping("/{eventType}/{channel}")
    public ResponseEntity<Void> setPref(@PathVariable String eventType,
                                        @PathVariable String channel,
                                        @Valid @RequestBody SetPrefRequest req) {
        service.setPref(SecurityUtils.requireCurrentUserId(), eventType, channel, req.enabled());
        return ResponseEntity.noContent().build();
    }

    // ── Do-Not-Disturb ──────────────────────────────────────────────────────────

    @GetMapping("/dnd")
    public ResponseEntity<DndResponse> getDnd() {
        return ResponseEntity.ok(DndResponse.of(service.getDnd(SecurityUtils.requireCurrentUserId())));
    }

    @PutMapping("/dnd")
    public ResponseEntity<DndResponse> updateDnd(@RequestBody UpdateDndRequest req) {
        return ResponseEntity.ok(DndResponse.of(
                service.updateDnd(SecurityUtils.requireCurrentUserId(), req)));
    }

    // ── Push tokens ─────────────────────────────────────────────────────────────

    @GetMapping("/push-tokens")
    public ResponseEntity<List<PushTokenResponse>> pushTokens() {
        return ResponseEntity.ok(pushTokenService.list(SecurityUtils.requireCurrentUserId())
                .stream().map(PushTokenResponse::of).toList());
    }

    @PostMapping("/push-tokens")
    public ResponseEntity<PushTokenResponse> registerPushToken(
            @Valid @RequestBody RegisterPushTokenRequest req) {
        return ResponseEntity.ok(PushTokenResponse.of(pushTokenService.register(
                SecurityUtils.requireCurrentUserId(), req.sid(), req.provider(), req.token(), req.platform())));
    }

    @DeleteMapping("/push-tokens/{id}")
    public ResponseEntity<Void> deletePushToken(@PathVariable UUID id) {
        pushTokenService.delete(SecurityUtils.requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
