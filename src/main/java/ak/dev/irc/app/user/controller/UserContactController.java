package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.settings.contacts.ContactSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @deprecated legacy alias of the spec-named contact-sync surface at
 * {@code /api/v1/contacts} — see {@link ak.dev.irc.app.settings.contacts.ContactsController}.
 *
 * <p>It used to reach {@code ContactMatchService} directly, which meant it
 * carried <b>neither the rate limit nor the consent record</b> its twin
 * enforced. Since both endpoints return the same {@code matched} count, the
 * unmetered one was a perfect membership oracle: post one hash, read the count,
 * repeat forever — the whole user base enumerable by phone number, with the
 * documented "3 syncs per 24h" protection one path segment away. Both routes now
 * funnel through {@link ContactSyncService}, so the limit, the consent event and
 * the suggestion recompute apply identically wherever the client knocks.</p>
 */
@Deprecated
@RestController
@RequestMapping("/api/v1/users/contacts")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class UserContactController {

    private final ContactSyncService contactSyncService;

    public record SyncContactsRequest(List<String> hashes) {}

    /** Upload (replace) the caller's hashed contacts. Returns stored + matched counts. */
    @PostMapping("/sync")
    public ResponseEntity<ContactSyncService.SyncResult> sync(
            @RequestBody(required = false) SyncContactsRequest body) {
        return ResponseEntity.ok(contactSyncService.sync(
                body == null ? List.of() : body.hashes(), null));
    }

    /** Privacy op: wipe the caller's uploaded contact hashes. */
    @DeleteMapping
    public ResponseEntity<Void> clear() {
        contactSyncService.clear();
        return ResponseEntity.noContent().build();
    }
}
