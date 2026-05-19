package ak.dev.irc.app.audit.cassandra.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit log partitioned by (resource_type, resource_id). Answers "what
 * happened to this resource?" 180-day TTL handled at the table level.
 */
@Table("audit_log_by_resource")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLogByResourceEntity {

    @PrimaryKeyColumn(name = "resource_type", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private String resourceType;

    @PrimaryKeyColumn(name = "resource_id", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private UUID resourceId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "audit_id", ordinal = 3, type = PrimaryKeyType.CLUSTERED)
    private UUID auditId;

    @Column("user_id")      private UUID    userId;
    @Column("username")     private String  username;
    @Column("operation")    private String  operation;
    @Column("outcome")      private String  outcome;
    @Column("http_method")  private String  httpMethod;
    @Column("path")         private String  path;
    @Column("query_string") private String  queryString;
    @Column("status_code")  private Integer statusCode;
    @Column("duration_ms")  private Long    durationMs;
    @Column("ip_address")   private String  ipAddress;
    @Column("user_agent")   private String  userAgent;
    @Column("summary")      private String  summary;
    @Column("error_code")   private String  errorCode;
}
