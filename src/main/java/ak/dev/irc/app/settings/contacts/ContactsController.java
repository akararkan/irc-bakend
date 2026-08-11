package ak.dev.irc.app.settings.contacts;

import ak.dev.irc.app.user.service.ContactMatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Spec §3 contact-synchronization surface, spec-named at {@code /api/v1/contacts}
 * — "which people in my address book are already here?".
 *
 * <p>The client hashes locally and uploads only hex digests; raw phone numbers
 * and email addresses never reach the server. The rate limit, consent record and
 * suggestion recompute all live in {@link ContactSyncService}, shared with the
 * deprecated {@code /api/v1/users/contacts} alias so neither route can be used
 * to sidestep the other's protections.</p>
 */
@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ContactsController {

    private final ContactSyncService contactSyncService;

    /**
     * @param hashes lower-case hex SHA-256 digests. Bounded by {@code @Size} so an
     *               oversized array is rejected at binding time — the service-side
     *               cap only applies after Jackson has already materialised the
     *               whole list, which left a large POST able to exhaust the heap.
     */
    public record SyncRequest(
            @Size(max = ContactMatchService.MAX_HASHES_PER_SYNC,
                  message = "At most " + ContactMatchService.MAX_HASHES_PER_SYNC
                          + " contact hashes per sync")
            List<String> hashes,
            String appVersion) {}

    @PostMapping("/sync")
    public ResponseEntity<ContactSyncService.SyncResult> sync(
            @Valid @RequestBody SyncRequest req) {
        return ResponseEntity.ok(contactSyncService.sync(req.hashes(), req.appVersion()));
    }

    @DeleteMapping("/sync")
    public ResponseEntity<Void> clear() {
        contactSyncService.clear();
        return ResponseEntity.noContent().build();
    }
}
