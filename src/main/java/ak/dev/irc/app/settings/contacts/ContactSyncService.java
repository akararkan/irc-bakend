package ak.dev.irc.app.settings.contacts;

import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.post.cassandra.service.FriendSuggestionService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.consent.service.ConsentService;
import ak.dev.irc.app.user.service.ContactMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The one implementation of contact synchronization, behind both the spec-named
 * {@code /api/v1/contacts} surface and the deprecated {@code /api/v1/users/contacts}
 * alias.
 *
 * <p>Collapsing them matters for more than tidiness: the alias previously skipped
 * the rate limit and the consent record entirely, so the anti-enumeration ceiling
 * documented on one route was bypassable by calling the other. Every protection
 * now lives here, where no route can miss it:</p>
 *
 * <ul>
 *   <li><b>Rate limit</b> — 3 full syncs per 24h per account. An address book
 *       changes slowly; anything faster is a scrape.</li>
 *   <li><b>Consent</b> — recorded <em>before</em> the upload is stored, so the
 *       evidence trail can never show hashes held without a recorded grant, and
 *       only when the caller actually sent something.</li>
 *   <li><b>Recompute</b> — suggestions refresh on every sync, so matches surface
 *       immediately rather than waiting for an unrelated graph change.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactSyncService {

    private final ContactMatchService contactMatchService;
    private final ConsentService consentService;
    private final FriendSuggestionService suggestionService;
    private final RateLimiter rateLimiter;

    /**
     * @param stored  hashes persisted after de-duplication, hex validation and
     *                the {@link ContactMatchService#MAX_HASHES_PER_SYNC} cap
     * @param skipped entries the server discarded — malformed, duplicated, or
     *                past the cap. Reported explicitly so a client can tell
     *                "my batch was trimmed" from "nobody matched"
     * @param matched distinct registered accounts found, honouring each target's
     *                discoverability settings. A count only — never identities
     */
    public record SyncResult(int stored, int skipped, int matched) {}

    public SyncResult sync(List<String> hashes, String appVersion) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        rateLimiter.check("contact:sync", userId, 3, Duration.ofHours(24));

        int submitted = hashes == null ? 0 : hashes.size();
        // Consent precedes storage: an upload that is recorded but not consented
        // to is exactly the state the compliance report exists to flag.
        if (submitted > 0) {
            consentService.record(userId, "CONTACTS", true, appVersion);
        }
        int stored = contactMatchService.syncContacts(userId, hashes);
        int matched = contactMatchService.matchedContactIds(userId).size();
        suggestionService.recomputeFor(userId);
        return new SyncResult(stored, Math.max(0, submitted - stored), matched);
    }

    public void clear() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        contactMatchService.clearContacts(userId);
        consentService.record(userId, "CONTACTS", false, null);
        suggestionService.recomputeFor(userId);
    }
}
