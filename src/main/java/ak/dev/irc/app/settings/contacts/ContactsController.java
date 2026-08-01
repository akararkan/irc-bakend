package ak.dev.irc.app.settings.contacts;

import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.consent.service.ConsentService;
import ak.dev.irc.app.user.service.ContactMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Spec §3 contact-synchronization surface, spec-named at {@code /api/v1/contacts}.
 * A thin alias over the existing {@link ContactMatchService} (client-side hash
 * model) that additionally records a consent event (§14) and rate-limits full
 * syncs (§3.5: 3 per 24h) to block the "upload the whole national number range
 * and enumerate the user base" attack.
 */
@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ContactsController {

    private final ContactMatchService contactMatchService;
    private final ConsentService consentService;
    private final RateLimiter rateLimiter;

    public record SyncRequest(List<String> hashes, String appVersion) {}
    public record SyncResponse(int stored, int matched) {}

    @PostMapping("/sync")
    public ResponseEntity<SyncResponse> sync(@RequestBody SyncRequest req) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        rateLimiter.check("contact:sync", userId, 3, Duration.ofHours(24));
        int stored = contactMatchService.syncContacts(userId, req.hashes());
        consentService.record(userId, "CONTACTS", true, req.appVersion());
        int matched = contactMatchService.matchedContactIds(userId).size();
        return ResponseEntity.ok(new SyncResponse(stored, matched));
    }

    @DeleteMapping("/sync")
    public ResponseEntity<Void> clear() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        contactMatchService.clearContacts(userId);
        consentService.record(userId, "CONTACTS", false, null);
        return ResponseEntity.noContent().build();
    }
}
