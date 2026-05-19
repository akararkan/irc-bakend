package ak.dev.irc.app.audit.entity;

import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Plain in-process DTO carrying one audit-log row through the
 * interceptor → service → Cassandra-writer → publisher pipeline.
 *
 * <p>Not a JPA entity any more — the audit log itself lives in Cassandra
 * ({@code audit_log_by_user} + {@code audit_log_by_resource}). This class
 * stays as the value object the interceptor builds and the service
 * accepts, so call sites don't need to learn a new shape.</p>
 *
 * <p>{@code createdAt} stays {@code LocalDateTime} (UTC) for compatibility
 * with the existing mapper / DTO / SSE payload; the Cassandra writer
 * converts to {@code Instant} at the row boundary.</p>
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    private UUID            id;
    private UUID            userId;
    private String          username;
    private AuditOperation  operation;
    private AuditOutcome    outcome;
    private String          resourceType;
    private UUID            resourceId;
    private String          httpMethod;
    private String          path;
    private String          queryString;
    private Integer         statusCode;
    private Long            durationMs;
    private String          ipAddress;
    private String          userAgent;
    private String          summary;
    private String          errorCode;
    private LocalDateTime   createdAt;
}
