package ak.dev.irc.app.audit.mapper;

import ak.dev.irc.app.audit.dto.AuditLogResponse;
import ak.dev.irc.app.audit.entity.AuditLog;
import org.springframework.stereotype.Component;

@Component
public class AuditLogMapper {

    public AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(
                a.getId(),
                a.getUserId(),
                a.getUsername(),
                a.getOperation(),
                a.getOutcome(),
                a.getResourceType(),
                a.getResourceId(),
                a.getHttpMethod(),
                a.getPath(),
                a.getQueryString(),
                a.getStatusCode(),
                a.getDurationMs(),
                a.getIpAddress(),
                a.getUserAgent(),
                a.getSummary(),
                a.getErrorCode(),
                a.getCreatedAt()
        );
    }
}
