package ak.dev.irc.app.common;

import ak.dev.irc.app.common.enums.AuditAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditEntity {

    // ── When ──────────────────────────────────────────────────────────────────

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Who ───────────────────────────────────────────────────────────────────

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    // ── Where (IP + Device) ───────────────────────────────────────────────────

    @Column(name = "created_by_ip", updatable = false, length = 45)
    private String createdByIp;

    @Column(name = "updated_by_ip", length = 45)
    private String updatedByIp;

    @Column(name = "created_by_device", updatable = false, length = 300)
    private String createdByDevice;

    @Column(name = "updated_by_device", length = 300)
    private String updatedByDevice;

    // ── What ──────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "last_action", length = 30)
    private AuditAction lastAction;

    @Column(name = "action_note", length = 500)
    private String actionNote;

    // ── Lifecycle hooks ───────────────────────────────────────────────────────

    @PrePersist
    protected void onPrePersist() {
        captureRequestContext(true);
        if (lastAction == null) lastAction = AuditAction.CREATE;
    }

    @PreUpdate
    protected void onPreUpdate() {
        captureRequestContext(false);
        if (lastAction == null) lastAction = AuditAction.UPDATE;
    }

    private void captureRequestContext(boolean isCreate) {
        try {
            var reqAttrs = RequestContextHolder.getRequestAttributes();
            if (reqAttrs instanceof ServletRequestAttributes servletReqAttrs) {
                var request = servletReqAttrs.getRequest();
                String ip     = extractIp(request);
                String device = request.getHeader("User-Agent");
                String safeDevice = device != null
                        ? device.substring(0, Math.min(device.length(), 300)) : null;

                if (isCreate) {
                    this.createdByIp     = ip;
                    this.createdByDevice = safeDevice;
                } else {
                    this.updatedByIp     = ip;
                    this.updatedByDevice = safeDevice;
                }
            }
        } catch (Exception ignored) {
            // Non-HTTP contexts (batch jobs, tests) — silently skip
        }
    }

    private String extractIp(jakarta.servlet.http.HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip.trim();
        return request.getRemoteAddr();
    }

    // ── Helper: set action context ─────────────────────────────────────────────

    public void audit(AuditAction action, String note) {
        this.lastAction  = action;
        this.actionNote  = note;
    }

    public void audit(AuditAction action) {
        this.lastAction = action;
    }
}
