package ak.dev.irc.app.settings.notification.service;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import ak.dev.irc.app.settings.notification.repository.PushTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Push-token registry (spec §8 token hygiene). Tokens are per-session and deleted
 * on logout; a provider returning {@code UNREGISTERED} deletes the token so the
 * invalid-token rate doesn't poison delivery metrics.
 */
@Service
@RequiredArgsConstructor
public class PushTokenService {

    private final PushTokenRepository repo;

    @Transactional
    public PushToken register(UUID userId, UUID sid, String provider, String token, String platform) {
        PushToken existing = repo.findByToken(token).orElse(null);
        if (existing != null) {
            existing.setUserId(userId);
            existing.setSid(sid);
            existing.setProvider(provider);
            existing.setPlatform(platform);
            existing.setLastSeenAt(LocalDateTime.now());
            return repo.save(existing);
        }
        return repo.save(PushToken.builder()
                .userId(userId).sid(sid).provider(provider).token(token)
                .platform(platform).lastSeenAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<PushToken> list(UUID userId) {
        return repo.findByUserId(userId);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repo.findById(id)
                .filter(t -> t.getUserId().equals(userId))
                .ifPresent(repo::delete);
    }

    /** Called on logout (spec §8) — SEAM: wire into the session-revoke path. */
    @Transactional
    public void deleteForSession(UUID sid) {
        repo.deleteBySid(sid);
    }

    /** Delete a token a provider reported as UNREGISTERED. */
    @Transactional
    public void deleteByToken(String token) {
        repo.deleteByToken(token);
    }
}
