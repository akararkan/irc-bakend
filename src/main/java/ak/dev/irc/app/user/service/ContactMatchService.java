package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserContactHash;
import ak.dev.irc.app.user.repository.UserContactHashRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contact-matching engine — the strongest cold-start friend-suggestion
 * signal (per the friend-suggestion literature): "which registered users
 * are already in my address book?"
 *
 * <p>Privacy model: the client normalizes and SHA-256-hashes contacts
 * locally (emails: {@code sha256(lowercase(trim(email)))}; phones:
 * {@code sha256(E.164 digits)}) and uploads only hex hashes — raw contact
 * data never reaches the server. Matching is a hash join against
 * server-computed IDENTITY hashes of registered emails. Users can wipe
 * their uploaded hashes at any time ({@code DELETE /users/contacts}).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactMatchService {

    /** Upload cap per sync — an address book, not a scrape. */
    public static final int MAX_HASHES_PER_SYNC = 5000;

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final UserContactHashRepository hashRepo;
    private final UserRepository            userRepo;

    /**
     * Replace the owner's uploaded contact set with a fresh sync. Invalid
     * entries are skipped (not rejected — partial address books are fine).
     *
     * @return number of stored contact hashes.
     */
    @Transactional
    public int syncContacts(UUID ownerId, List<String> rawHashes) {
        Set<String> clean = new LinkedHashSet<>();
        if (rawHashes != null) {
            for (String h : rawHashes) {
                if (clean.size() >= MAX_HASHES_PER_SYNC) break;
                if (h == null) continue;
                String trimmed = h.trim().toLowerCase();
                if (SHA256_HEX.matcher(trimmed).matches()) clean.add(trimmed);
            }
        }
        hashRepo.deleteByOwnerIdAndKind(ownerId, UserContactHash.KIND_CONTACT);
        Instant now = Instant.now();
        List<UserContactHash> rows = new ArrayList<>(clean.size());
        for (String h : clean) {
            rows.add(UserContactHash.builder()
                    .ownerId(ownerId).hash(h)
                    .kind(UserContactHash.KIND_CONTACT)
                    .createdAt(now).build());
        }
        hashRepo.saveAll(rows);
        ensureIdentityHash(ownerId);
        log.info("[CONTACTS] user {} synced {} contact hashes", ownerId, rows.size());
        return rows.size();
    }

    /** Wipe the owner's uploaded contacts (privacy op). IDENTITY rows stay — they carry no contact data. */
    @Transactional
    public void clearContacts(UUID ownerId) {
        hashRepo.deleteByOwnerIdAndKind(ownerId, UserContactHash.KIND_CONTACT);
        log.info("[CONTACTS] user {} cleared synced contacts", ownerId);
    }

    /** Registered users found in the owner's uploaded contacts. Fail-open empty. */
    @Transactional(readOnly = true)
    public Set<UUID> matchedContactIds(UUID ownerId) {
        try {
            return new HashSet<>(hashRepo.findMatchedUserIds(ownerId));
        } catch (Exception e) {
            log.debug("[CONTACTS] match lookup failed for {}: {}", ownerId, e.getMessage());
            return Set.of();
        }
    }

    /** Matches where BOTH sides have each other saved — the strongest contact signal. */
    @Transactional(readOnly = true)
    public Set<UUID> bidirectionalMatchIds(UUID ownerId) {
        try {
            Set<UUID> mutual = new HashSet<>(hashRepo.findMatchedUserIds(ownerId));
            mutual.retainAll(new HashSet<>(hashRepo.findReverseMatchedUserIds(ownerId)));
            return mutual;
        } catch (Exception e) {
            log.debug("[CONTACTS] bidirectional lookup failed for {}: {}", ownerId, e.getMessage());
            return Set.of();
        }
    }

    /** Idempotent: write the server-side identity hash for one user if missing. */
    @Transactional
    public void ensureIdentityHash(UUID userId) {
        if (hashRepo.existsByOwnerIdAndKind(userId, UserContactHash.KIND_IDENTITY)) return;
        String email = userRepo.findById(userId).map(User::getEmail).orElse(null);
        if (email == null || email.isBlank()) return;
        hashRepo.save(UserContactHash.builder()
                .ownerId(userId)
                .hash(sha256Hex(email.trim().toLowerCase()))
                .kind(UserContactHash.KIND_IDENTITY)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Startup backfill: every active account gets an IDENTITY hash so it is
     * matchable by other users' synced contacts. Idempotent (only missing
     * rows are written); async so boot time is untouched.
     */
    @Async
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void backfillIdentityHashes() {
        try {
            List<Object[]> missing = hashRepo.findUsersMissingIdentityHash();
            if (missing.isEmpty()) return;
            Instant now = Instant.now();
            List<UserContactHash> rows = new ArrayList<>(missing.size());
            for (Object[] row : missing) {
                UUID id = (UUID) row[0];
                String email = (String) row[1];
                if (email == null || email.isBlank()) continue;
                rows.add(UserContactHash.builder()
                        .ownerId(id)
                        .hash(sha256Hex(email.trim().toLowerCase()))
                        .kind(UserContactHash.KIND_IDENTITY)
                        .createdAt(now).build());
            }
            hashRepo.saveAll(rows);
            log.info("[CONTACTS] identity-hash backfill wrote {} rows", rows.size());
        } catch (Exception e) {
            log.warn("[CONTACTS] identity-hash backfill failed: {}", e.getMessage());
        }
    }

    /** Hex SHA-256 — the exact function clients must apply to normalized contacts. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
