package ak.dev.irc.app.security.session;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.security.dto.SecurityDtos.SessionResponse;
import ak.dev.irc.app.user.entity.RefreshToken;
import ak.dev.irc.app.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Active-sessions surface (spec §12). A straight read of {@code refresh_tokens}
 * (the source of truth for logins) plus per-session revoke and trust. Revoking a
 * session flips {@code is_revoked} (so it can no longer refresh) and denylists
 * its {@code sid} for the remaining access-token window.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RefreshTokenRepository refreshTokenRepo;
    private final SessionDenylist denylist;

    @Transactional(readOnly = true)
    public List<SessionResponse> activeSessions(UUID userId) {
        return refreshTokenRepo.findActiveSessions(userId, LocalDateTime.now())
                .stream().map(SessionResponse::of).toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID sid) {
        RefreshToken token = refreshTokenRepo.findBySid(sid)
                .orElseThrow(() -> new ResourceNotFoundException("Session", "sid", sid));
        if (token.getUser() == null || !token.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Session", "sid", sid); // don't leak others' sessions
        }
        refreshTokenRepo.revokeBySid(sid, LocalDateTime.now());
        denylist.deny(sid, Duration.ofMinutes(15));
    }

    @Transactional
    public void trustDevice(UUID userId, UUID sid, int days) {
        RefreshToken token = refreshTokenRepo.findBySid(sid)
                .orElseThrow(() -> new ResourceNotFoundException("Session", "sid", sid));
        if (token.getUser() == null || !token.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Session", "sid", sid);
        }
        int clamped = Math.max(1, Math.min(days, 90));
        refreshTokenRepo.trustBySid(sid, LocalDateTime.now().plusDays(clamped));
    }
}
