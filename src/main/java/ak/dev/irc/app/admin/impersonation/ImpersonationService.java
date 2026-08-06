package ak.dev.irc.app.admin.impersonation;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.security.jwt.JwtTokenProvider;
import ak.dev.irc.app.security.session.SessionDenylist;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only "view as user" (architecture.md §7, user-administration.md §5).
 *
 * <p>Hard rules enforced end-to-end:
 * <ul>
 *   <li>The minted token carries ONLY {@code ROLE_IMPERSONATED_READ} — never
 *       the target's roles; {@code JwtAuthenticationFilter} rejects every
 *       non-GET request before the SecurityContext is even populated.</li>
 *   <li>TTL ≤ 15 min, one active session per admin, revocable via the
 *       {@code sid:denied:*} denylist.</li>
 *   <li>Reason is mandatory (min 10 chars); start/end write dual-id audit
 *       rows; every request under the token is interceptor-attributed to the
 *       admin with an {@code impersonating=} marker.</li>
 *   <li>Admins are never impersonatable.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpersonationService {

    static final Duration TTL = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "impersonation:";

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final SessionDenylist sessionDenylist;
    private final StringRedisTemplate redis;
    private final AdminAuditor adminAuditor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImpersonationGrant(String token, UUID targetUserId, String targetUsername,
                                     Instant expiresAt) {
    }

    public ImpersonationGrant start(User admin, UUID targetUserId, String reason) {
        if (reason == null || reason.trim().length() < 10) {
            throw new BadRequestException(
                    "A reason of at least 10 characters is required to impersonate.",
                    "IMPERSONATION_REASON_REQUIRED");
        }
        if (admin.getId().equals(targetUserId)) {
            throw new BadRequestException("You cannot impersonate yourself.", "SELF_ACTION_FORBIDDEN");
        }
        User target = userRepository.findActiveById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetUserId));
        if (target.getRole() == Role.ADMIN) {
            throw new ForbiddenException("Admins cannot be impersonated.", "IMPERSONATION_TARGET_ADMIN");
        }

        // One active impersonation per admin: starting a new one revokes the old.
        endInternal(admin, false);

        UUID sid = UUID.randomUUID();
        String token = jwtTokenProvider.generateImpersonationToken(admin, target, sid, TTL.toMillis());
        try {
            redis.opsForValue().set(KEY_PREFIX + admin.getId(),
                    sid + "|" + targetUserId, TTL);
        } catch (Exception e) {
            log.warn("[IMPERSONATION] redis marker write failed: {}", e.getMessage());
        }

        adminAuditor.record(AuditOperation.OTHER, "User", targetUserId,
                "ADMIN_IMPERSONATE_START",
                "admin=" + admin.getId() + ", target=" + targetUserId + ", reason=" + reason.trim());
        log.info("[IMPERSONATION] admin {} started read-only impersonation of {} (sid={})",
                admin.getId(), targetUserId, sid);
        return new ImpersonationGrant(token, target.getId(), target.getUsername(),
                Instant.now().plus(TTL));
    }

    public void end(User admin) {
        endInternal(admin, true);
    }

    private void endInternal(User admin, boolean audit) {
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(KEY_PREFIX + admin.getId());
        } catch (Exception e) {
            log.warn("[IMPERSONATION] redis marker read failed: {}", e.getMessage());
            return;
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split("\\|", 2);
        try {
            UUID sid = UUID.fromString(parts[0]);
            sessionDenylist.deny(sid, TTL);
        } catch (Exception ignored) {
        }
        if (audit) {
            UUID target = parts.length > 1 ? safeUuid(parts[1]) : null;
            adminAuditor.record(AuditOperation.OTHER, "User", target,
                    "ADMIN_IMPERSONATE_END", "admin=" + admin.getId());
            log.info("[IMPERSONATION] admin {} ended impersonation of {}", admin.getId(), target);
        }
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
