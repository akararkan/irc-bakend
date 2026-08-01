package ak.dev.irc.app.settings.presence.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.presence.entity.UserPresencePolicy;
import ak.dev.irc.app.settings.presence.service.PresencePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Online-presence settings (spec §7). Everyone / Friends / Nobody policies for
 * online status and last seen.
 */
@RestController
@RequestMapping("/api/v1/settings/presence")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PresenceSettingsController {

    private final PresencePolicyService service;

    public record PresenceResponse(String onlineStatusPolicy, String lastSeenPolicy) {
        static PresenceResponse of(UserPresencePolicy p) {
            return new PresenceResponse(p.getOnlineStatusPolicy().name(), p.getLastSeenPolicy().name());
        }
    }

    public record UpdatePresenceRequest(String onlineStatusPolicy, String lastSeenPolicy) {}

    @GetMapping
    public ResponseEntity<PresenceResponse> get() {
        return ResponseEntity.ok(PresenceResponse.of(
                service.get(SecurityUtils.requireCurrentUserId())));
    }

    @PutMapping
    public ResponseEntity<PresenceResponse> update(@RequestBody UpdatePresenceRequest req) {
        return ResponseEntity.ok(PresenceResponse.of(service.update(
                SecurityUtils.requireCurrentUserId(),
                req.onlineStatusPolicy(), req.lastSeenPolicy())));
    }
}
