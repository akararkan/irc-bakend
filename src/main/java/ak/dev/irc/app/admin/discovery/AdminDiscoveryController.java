package ak.dev.irc.app.admin.discovery;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.messages.AdminContentMessages;
import ak.dev.irc.app.post.cassandra.service.FriendSuggestionService;
import ak.dev.irc.app.settings.consent.service.ConsentService;
import ak.dev.irc.app.settings.discovery.qr.repository.QrTokenRepository;
import ak.dev.irc.app.settings.discovery.qr.service.QrTokenService;
import ak.dev.irc.app.settings.discovery.service.DiscoverabilityService;
import ak.dev.irc.app.user.entity.UserContactHash;
import ak.dev.irc.app.user.repository.SuggestionDismissalRepository;
import ak.dev.irc.app.user.repository.UserContactHashRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Discovery / PYMK / contact-sync oversight (blueprint §3.13,
 * discovery-pymk-privacy.md §7): per-user PYMK inspection + recompute,
 * contact-sync privacy stats, the consent∧hash compliance report, GDPR
 * contact-hash purge, discovery-flag inspection, and forced QR rotation.
 * Raw hashes are never exposed to anyone — only counts.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiscoveryController {

    private static final long NEAR_CAP_THRESHOLD = 4500;

    private final FriendSuggestionService friendSuggestionService;
    private final SuggestionDismissalRepository dismissalRepository;
    private final UserContactHashRepository contactHashRepository;
    private final ConsentService consentService;
    private final DiscoverabilityService discoverabilityService;
    private final QrTokenService qrTokenService;
    private final QrTokenRepository qrTokenRepository;
    private final AdminAuditor adminAuditor;

    // ── PYMK per-user (D2/D3) ───────────────────────────────────────────

    @PostMapping("/users/{userId}/suggestions/recompute")
    public ResponseEntity<Void> recompute(@PathVariable UUID userId) {
        friendSuggestionService.recomputeFor(userId);
        adminAuditor.record(AuditOperation.OTHER, "User", userId, "ADMIN_PYMK_RECOMPUTE");
        return ResponseEntity.accepted().build();
    }

    /** Cross-user matching data — PII-classed, therefore step-up + audited. */
    @GetMapping("/users/{userId}/suggestions")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> suggestions(@PathVariable UUID userId) {
        adminAuditor.record(AuditOperation.READ, "User", userId, "ADMIN_PYMK_VIEW");
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (var s : friendSuggestionService.topSuggestionsFor(userId, 50)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidateId", s.getCandidateId());
            row.put("storedScore", s.getScore());
            row.put("reason", s.getReason());
            row.put("computedAt", s.getComputedAt());
            suggestions.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("suggestions", suggestions);
        body.put("dismissedCandidateIds", dismissalRepository.findDismissedCandidateIds(userId));
        return ResponseEntity.ok(body);
    }

    // ── contact-sync stats + compliance (D4/D5) ─────────────────────────

    @GetMapping("/discovery/contact-sync/stats")
    public ResponseEntity<Map<String, Object>> contactSyncStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contactHashRows", contactHashRepository.countByKind(UserContactHash.KIND_CONTACT));
        body.put("usersWithSyncedContacts",
                contactHashRepository.countDistinctOwnersByKind(UserContactHash.KIND_CONTACT));
        body.put("identityHashRows", contactHashRepository.countByKind(UserContactHash.KIND_IDENTITY));
        List<Map<String, Object>> nearCap = new ArrayList<>();
        for (Object[] row : contactHashRepository.ownersNearCap(NEAR_CAP_THRESHOLD)) {
            nearCap.add(Map.of("ownerId", row[0], "hashes", ((Number) row[1]).longValue()));
        }
        body.put("ownersNearCap", nearCap);
        body.put("capPerSync", 5000);
        body.put("notes", List.of(
                AdminContentMessages.NOTE_CONTACT_HASHES_SHA256,
                AdminContentMessages.NOTE_IDENTITY_HASH_BACKFILL,
                AdminContentMessages.NOTE_CONTACT_SYNC_RATE_LIMIT));
        return ResponseEntity.ok(body);
    }

    /** Users holding synced hashes without an active CONTACTS consent. */
    @GetMapping("/discovery/contact-sync/compliance")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> compliance(
            @RequestParam(defaultValue = "500") int scanLimit) {
        adminAuditor.record(AuditOperation.READ, "User", null, "ADMIN_CONTACT_COMPLIANCE_VIEW");
        int cap = Math.max(1, Math.min(scanLimit, 2000));
        List<UUID> owners = contactHashRepository.findDistinctContactOwners(PageRequest.of(0, cap));
        List<UUID> mismatches = new ArrayList<>();
        for (UUID owner : owners) {
            boolean consented;
            try {
                consented = consentService.currentState(owner, "CONTACTS");
            } catch (Exception e) {
                consented = false;
            }
            if (!consented) {
                mismatches.add(owner);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownersScanned", owners.size());
        body.put("scanTruncated", owners.size() >= cap);
        body.put("ownersWithoutActiveConsent", mismatches);
        return ResponseEntity.ok(body);
    }

    // ── purge / flags / QR (D6/D7/D8) ───────────────────────────────────

    @PostMapping("/users/{userId}/contact-hashes/purge")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Map<String, Object>> purgeContactHashes(@PathVariable UUID userId) {
        long before = contactHashRepository.countByOwnerIdAndKind(userId, UserContactHash.KIND_CONTACT)
                + contactHashRepository.countByOwnerIdAndKind(userId, UserContactHash.KIND_IDENTITY);
        contactHashRepository.deleteByOwnerIdAndKind(userId, UserContactHash.KIND_CONTACT);
        contactHashRepository.deleteByOwnerIdAndKind(userId, UserContactHash.KIND_IDENTITY);
        adminAuditor.record(AuditOperation.DELETE, "User", userId,
                "ADMIN_CONTACT_HASH_PURGE", "rows=" + before);
        return ResponseEntity.ok(Map.of("deleted", before));
    }

    @GetMapping("/users/{userId}/discovery")
    public ResponseEntity<Map<String, Object>> discoveryFlags(@PathVariable UUID userId) {
        var flags = discoverabilityService.get(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("byUsername", flags.isByUsername());
        body.put("byPhone", flags.isByPhone());
        body.put("byEmail", flags.isByEmail());
        body.put("byQr", flags.isByQr());
        body.put("indexable", flags.isIndexable());
        qrTokenRepository.findByUserId(userId).ifPresentOrElse(qr -> {
            body.put("qrTokenActive", true);
            body.put("qrRotatedAt", qr.getRotatedAt());
        }, () -> body.put("qrTokenActive", false));
        body.put("knownSeam", AdminContentMessages.WARN_QR_RESOLVE_SEAM);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/users/{userId}/qr/rotate")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> rotateQr(@PathVariable UUID userId) {
        var token = qrTokenService.rotate(userId);
        adminAuditor.record(AuditOperation.UPDATE, "User", userId, "ADMIN_QR_ROTATE");
        return ResponseEntity.ok(Map.of(
                "opaqueToken", token.getOpaqueToken(),
                "rotatedAt", token.getRotatedAt()));
    }
}
