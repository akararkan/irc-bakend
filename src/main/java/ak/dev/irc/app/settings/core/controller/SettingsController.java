package ak.dev.irc.app.settings.core.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.core.dto.SettingsResponse;
import ak.dev.irc.app.settings.core.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Core settings surface (spec §22.3) for the cosmetic/structured blocks.
 * Enforced sections (privacy, security, notifications, presence, discovery,
 * safety) have their own dedicated controllers under {@code /api/v1/settings/*}
 * and {@code /api/v1/security}.
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SettingsController {

    private final SettingsService settingsService;

    /** Full resolved settings object, defaults merged. */
    @GetMapping
    public ResponseEntity<SettingsResponse> getAll() {
        return ResponseEntity.ok(settingsService.resolve(SecurityUtils.requireCurrentUserId()));
    }

    /** One cosmetic section: appearance | accessibility | messages | media. */
    @GetMapping("/{section}")
    public ResponseEntity<Object> getSection(@PathVariable String section) {
        return ResponseEntity.ok(
                settingsService.getSection(SecurityUtils.requireCurrentUserId(), section));
    }

    /** Partial update (JSON Merge Patch) of a cosmetic section. */
    @PatchMapping("/{section}")
    public ResponseEntity<Object> patchSection(@PathVariable String section,
                                               @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(
                settingsService.patchSection(SecurityUtils.requireCurrentUserId(), section, patch));
    }

    // ── Explicit PUT replace endpoints (spec §10, §11, §9, §20.3) ──────────────

    @PutMapping("/appearance")
    public ResponseEntity<Object> putAppearance(@RequestBody Map<String, Object> body) {
        return replace("appearance", body);
    }

    @PutMapping("/accessibility")
    public ResponseEntity<Object> putAccessibility(@RequestBody Map<String, Object> body) {
        return replace("accessibility", body);
    }

    @PutMapping("/messages")
    public ResponseEntity<Object> putMessages(@RequestBody Map<String, Object> body) {
        return replace("messages", body);
    }

    @PutMapping("/media")
    public ResponseEntity<Object> putMedia(@RequestBody Map<String, Object> body) {
        return replace("media", body);
    }

    private ResponseEntity<Object> replace(String section, Map<String, Object> body) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(settingsService.replaceSection(userId, section, body));
    }
}
