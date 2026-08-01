package ak.dev.irc.app.security.twofa.service;

import ak.dev.irc.app.security.twofa.TwoFaProperties;
import ak.dev.irc.app.security.twofa.entity.RecoveryCode;
import ak.dev.irc.app.security.twofa.repository.RecoveryCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 2FA recovery codes (spec §12). Ten single-use codes generated once, displayed
 * once, stored only as hashes. Consumption is single-use; regenerating a set
 * invalidates the previous set entirely.
 *
 * <p>The spec calls for Argon2id hashing; this offline build reuses the
 * platform's configured {@link PasswordEncoder} (BCrypt) — codes are
 * high-entropy so BCrypt's work factor is more than sufficient. Swap the encoder
 * to Argon2id when a Bouncy Castle dependency can be added.</p>
 */
@Service
@RequiredArgsConstructor
public class RecoveryCodeService {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no I,L,O,0,1
    private final SecureRandom random = new SecureRandom();

    private final RecoveryCodeRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final TwoFaProperties props;

    /** Regenerate the whole set; returns the plaintext codes ONCE. */
    @Transactional
    public List<String> regenerate(UUID userId) {
        repo.deleteAllForUser(userId);
        List<String> plaintext = new ArrayList<>(props.getRecoveryCodeCount());
        List<RecoveryCode> rows = new ArrayList<>(props.getRecoveryCodeCount());
        for (int i = 0; i < props.getRecoveryCodeCount(); i++) {
            String code = randomCode();
            plaintext.add(code);
            rows.add(RecoveryCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code))
                    .build());
        }
        repo.saveAll(rows);
        return plaintext;
    }

    /**
     * Consume a recovery code if it matches an unused one. Returns true on a
     * successful single-use consumption.
     */
    @Transactional
    public boolean consume(UUID userId, String code) {
        if (code == null || code.isBlank()) return false;
        String normalized = code.trim().toUpperCase();
        for (RecoveryCode rc : repo.findByUserIdAndUsedAtIsNull(userId)) {
            if (passwordEncoder.matches(normalized, rc.getCodeHash())) {
                rc.setUsedAt(java.time.LocalDateTime.now());
                repo.save(rc);
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public long remaining(UUID userId) {
        return repo.countByUserIdAndUsedAtIsNull(userId);
    }

    /** Drop every recovery code for a user — called when 2FA is disabled. */
    @Transactional
    public void clear(UUID userId) {
        repo.deleteAllForUser(userId);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
