package ak.dev.irc.app.settings.discovery.qr.controller;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.SettingsMessages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.discovery.qr.dto.QrDtos.QrResolveResponse;
import ak.dev.irc.app.settings.discovery.qr.dto.QrDtos.QrTokenResponse;
import ak.dev.irc.app.settings.discovery.qr.service.QrTokenService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * QR-code discovery endpoints (spec §6). The owner endpoints live under the
 * settings tree; the public resolve endpoint lives outside it. No class-level
 * {@code @RequestMapping} so the resolve path can sit at its own root.
 */
@RestController
@RequiredArgsConstructor
public class QrDiscoveryController {

    private final QrTokenService qrTokenService;
    private final UserRepository userRepository;

    /** The current user's QR token (minted on first request). */
    @GetMapping("/api/v1/settings/discovery/qr")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QrTokenResponse> myQr() {
        return ResponseEntity.ok(QrTokenResponse.of(
                qrTokenService.getOrCreate(SecurityUtils.requireCurrentUserId())));
    }

    /** Rotate the current user's QR token — old printed codes stop working. */
    @PostMapping("/api/v1/settings/discovery/qr/rotate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QrTokenResponse> rotate() {
        return ResponseEntity.ok(QrTokenResponse.of(
                qrTokenService.rotate(SecurityUtils.requireCurrentUserId())));
    }

    /** Resolve a scanned opaque token to the target's public profile card. */
    @GetMapping("/api/v1/discovery/qr/resolve/{opaque}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QrResolveResponse> resolve(@PathVariable String opaque) {
        UUID targetId = qrTokenService.resolve(opaque);
        // SEAM: once the discoverability-flags service (settings.discovery) lands,
        // additionally reject here when the target user has disabled discover.byQr.
        User user = userRepository.findActiveById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException(SettingsMessages.QR_USER_NOT_FOUND_MSG));
        return ResponseEntity.ok(new QrResolveResponse(
                user.getId(), user.getUsername(), user.getFullName(), user.getProfileImage()));
    }
}
