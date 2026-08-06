package ak.dev.irc.app.admin.support;

import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.service.AuditLogService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one funnel every admin mutation uses to write its business audit row —
 * the {@code ADMIN_{DOMAIN}_{VERB}} actions of the admin blueprint (§6
 * audit-action registry). The HTTP interceptor still writes its request row
 * for free; this adds the named business event with a human-readable summary.
 * <p>
 * First real caller of {@link AuditLogService#record} (which shipped with zero
 * call sites). Never throws — auditing must not fail the action.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditor {

    private static final int MAX_SUMMARY = 500;

    private final AuditLogService auditLogService;

    /** Writes "{action}" (plus detail when given) attributed to the current admin. */
    public void record(AuditOperation operation, String resourceType, UUID resourceId,
                       String action, String detail) {
        User admin = SecurityUtils.getCurrentUser().orElse(null);
        String summary = (detail == null || detail.isBlank())
                ? action
                : action + " — " + detail;
        if (summary.length() > MAX_SUMMARY) {
            summary = summary.substring(0, MAX_SUMMARY);
        }
        auditLogService.record(
                admin != null ? admin.getId() : null,
                admin != null ? admin.getUsername() : "system",
                operation,
                resourceType,
                resourceId,
                summary);
    }

    public void record(AuditOperation operation, String resourceType, UUID resourceId, String action) {
        record(operation, resourceType, resourceId, action, null);
    }
}
