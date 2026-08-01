package ak.dev.irc.app.settings.audit.service;

import ak.dev.irc.app.settings.audit.entity.SettingsAudit;
import ak.dev.irc.app.settings.audit.repository.SettingsAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Central writer for the settings audit trail. Every settings mutation of a
 * privacy- or security-relevant kind calls {@link #record}. Best-effort: an
 * audit failure must never fail the user's actual settings write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsAuditService {

    private final SettingsAuditRepository repo;

    @Transactional
    public void record(UUID userId, String settingKey, String oldValue, String newValue) {
        try {
            repo.save(SettingsAudit.builder()
                    .userId(userId)
                    .settingKey(settingKey)
                    .oldValue(truncate(oldValue))
                    .newValue(truncate(newValue))
                    .ip(currentIp())
                    .build());
        } catch (Exception ex) {
            log.warn("[SETTINGS-AUDIT] failed to record {} for user {}: {}",
                    settingKey, userId, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<SettingsAudit> history(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    private static String truncate(String v) {
        if (v == null) return null;
        return v.length() > 4000 ? v.substring(0, 4000) : v;
    }

    private static String currentIp() {
        try {
            if (RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attrs) {
                var req = attrs.getRequest();
                String fwd = req.getHeader("X-Forwarded-For");
                if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
                return req.getRemoteAddr();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
