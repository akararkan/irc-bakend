package ak.dev.irc.app.settings.discovery.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.discovery.entity.UserDiscoverability;
import ak.dev.irc.app.settings.discovery.service.DiscoverabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Search & discovery flags surface (spec §6): allow-search-by-username / phone /
 * email / QR, and search-engine indexing. The QR token endpoints live in the
 * sibling {@code settings.discovery.qr} controller.
 */
@RestController
@RequestMapping("/api/v1/settings/discovery")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DiscoverySettingsController {

    private final DiscoverabilityService service;

    public record DiscoverabilityResponse(boolean byUsername, boolean byPhone, boolean byEmail,
                                          boolean byQr, boolean indexable) {
        static DiscoverabilityResponse of(UserDiscoverability d) {
            return new DiscoverabilityResponse(d.isByUsername(), d.isByPhone(), d.isByEmail(),
                    d.isByQr(), d.isIndexable());
        }
    }

    public record UpdateDiscoverabilityRequest(Boolean byUsername, Boolean byPhone, Boolean byEmail,
                                               Boolean byQr, Boolean indexable) {}

    @GetMapping
    public ResponseEntity<DiscoverabilityResponse> get() {
        return ResponseEntity.ok(DiscoverabilityResponse.of(
                service.get(SecurityUtils.requireCurrentUserId())));
    }

    @PutMapping
    public ResponseEntity<DiscoverabilityResponse> update(@RequestBody UpdateDiscoverabilityRequest req) {
        return ResponseEntity.ok(DiscoverabilityResponse.of(service.update(
                SecurityUtils.requireCurrentUserId(),
                req.byUsername(), req.byPhone(), req.byEmail(), req.byQr(), req.indexable())));
    }
}
