package ak.dev.irc.app.settings.consent.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.consent.dto.ConsentDtos.*;
import ak.dev.irc.app.settings.consent.service.ConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Consent-evidence surface (spec §14). The client records each grant/revocation
 * of a data-sharing permission; this is the audit trail for §3 contact sync.
 */
@RestController
@RequestMapping("/api/v1/settings/consent")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ConsentController {

    private final ConsentService consentService;

    @PostMapping
    public ResponseEntity<ConsentEventResponse> record(@Valid @RequestBody RecordConsentRequest req) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(ConsentEventResponse.of(
                consentService.record(userId, req.scope(), req.granted(), req.appVersion())));
    }

    @GetMapping
    public ResponseEntity<Page<ConsentEventResponse>> history(Pageable pageable) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(consentService.history(userId, pageable)
                .map(ConsentEventResponse::of));
    }

    @GetMapping("/{scope}")
    public ResponseEntity<ConsentStateResponse> state(@PathVariable String scope) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        boolean granted = consentService.currentState(userId, scope);
        return ResponseEntity.ok(new ConsentStateResponse(scope.toUpperCase(), granted));
    }
}
