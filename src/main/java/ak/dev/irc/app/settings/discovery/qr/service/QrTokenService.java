package ak.dev.irc.app.settings.discovery.qr.service;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.SettingsMessages;
import ak.dev.irc.app.settings.discovery.qr.entity.QrToken;
import ak.dev.irc.app.settings.discovery.qr.repository.QrTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints, rotates and resolves the opaque QR-discovery tokens (spec §6).
 *
 * <p>The opaque value is 24 bytes of {@link SecureRandom} entropy, URL-safe
 * base64 without padding (32 chars) — unguessable, so nobody can walk the user
 * space by iterating ids. Rotation replaces the value so old printed codes stop
 * resolving.</p>
 */
@Service
@RequiredArgsConstructor
public class QrTokenService {

    /** A few retries cover the astronomically rare unique-index clash. */
    private static final int MINT_ATTEMPTS = 5;
    private static final int TOKEN_BYTES   = 24;

    private final QrTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public QrToken getOrCreate(UUID userId) {
        return repo.findByUserId(userId).orElseGet(() ->
                repo.save(QrToken.builder()
                        .userId(userId)
                        .opaqueToken(freshToken())
                        .build()));
    }

    @Transactional
    public QrToken rotate(UUID userId) {
        QrToken token = getOrCreate(userId);
        token.setOpaqueToken(freshToken());
        token.setRotatedAt(LocalDateTime.now());
        return repo.save(token);
    }

    /** Resolve an opaque token to its owning user id, or 404. */
    @Transactional(readOnly = true)
    public UUID resolve(String opaque) {
        return repo.findByOpaqueToken(opaque)
                .map(QrToken::getUserId)
                .orElseThrow(() -> new ResourceNotFoundException(SettingsMessages.QR_CODE_INVALID_MSG));
    }

    /** Generate a token guaranteed unique against the current table. */
    private String freshToken() {
        for (int i = 0; i < MINT_ATTEMPTS; i++) {
            String candidate = randomToken();
            if (repo.findByOpaqueToken(candidate).isEmpty()) return candidate;
        }
        // Extremely unlikely; fall back to a UUID-seeded value.
        return randomToken() + Long.toHexString(random.nextLong());
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
