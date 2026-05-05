package ak.dev.irc.app.audit.entity;

import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable record of a single user action.
 *
 * <p>Indexed so admins can scope queries to a user, a resource, or a time
 * window without sequential scans. Rows are insert-only — there is no update
 * path (audit log can't be tampered with through the application).</p>
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_user_created",     columnList = "user_id, created_at DESC"),
        @Index(name = "idx_audit_resource",         columnList = "resource_type, resource_id"),
        @Index(name = "idx_audit_operation",        columnList = "operation, created_at DESC"),
        @Index(name = "idx_audit_outcome",          columnList = "outcome"),
        @Index(name = "idx_audit_created_at",       columnList = "created_at DESC")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Authenticated principal id. Null for anonymous traffic. */
    @Column(name = "user_id")
    private UUID userId;

    /** Denormalised username at the time of the action — survives user deletion. */
    @Column(name = "username", length = 80)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    private AuditOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private AuditOutcome outcome;

    /** Logical resource type (Post, Question, User, Research, …). */
    @Column(name = "resource_type", length = 60)
    private String resourceType;

    /** Optional id of the affected row (best-effort path-extracted). */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "path", length = 500)
    private String path;

    @Column(name = "query_string", length = 1000)
    private String queryString;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "summary", length = 500)
    private String summary;

    /** Application error code on failure (matches AppException codes). */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
