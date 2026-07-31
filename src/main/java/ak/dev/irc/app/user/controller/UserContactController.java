package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.post.cassandra.service.FriendSuggestionService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.service.ContactMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Contact synchronization — the client uploads SHA-256 hashes of address-book
 * entries (never raw contacts) to power the contact-matching friend-suggestion
 * signal.
 *
 * <p>Client-side hashing contract: emails → {@code sha256(lowercase(trim(email)))},
 * phones → {@code sha256(E.164 digits, no '+')}, hex-encoded. Max
 * {@link ContactMatchService#MAX_HASHES_PER_SYNC} hashes per sync; each sync
 * replaces the previous upload. A successful sync immediately triggers a
 * suggestion recompute so matches surface right away.</p>
 */
@RestController
@RequestMapping("/api/v1/users/contacts")
@RequiredArgsConstructor
public class UserContactController {

    private final ContactMatchService      contactMatchService;
    private final FriendSuggestionService  suggestionService;

    public record SyncContactsRequest(List<String> hashes) {}

    /** Upload (replace) the caller's hashed contacts. Returns stored + matched counts. */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody SyncContactsRequest body,
                                                    @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        int stored = contactMatchService.syncContacts(user.getId(),
                body == null ? List.of() : body.hashes());
        int matched = contactMatchService.matchedContactIds(user.getId()).size();
        suggestionService.recomputeFor(user.getId());   // async — matches surface immediately
        return ResponseEntity.ok(Map.of("stored", stored, "matched", matched));
    }

    /** Privacy op: wipe the caller's uploaded contact hashes. */
    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        contactMatchService.clearContacts(user.getId());
        suggestionService.recomputeFor(user.getId());
        return ResponseEntity.noContent().build();
    }
}
