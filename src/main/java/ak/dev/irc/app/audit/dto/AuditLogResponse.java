package ak.dev.irc.app.audit.dto;

import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        UUID            id,
        UUID            userId,
        String          username,
        AuditOperation  operation,
        AuditOutcome    outcome,
        String          resourceType,
        UUID            resourceId,
        String          httpMethod,
        String          path,
        String          queryString,
        Integer         statusCode,
        Long            durationMs,
        String          ipAddress,
        String          userAgent,
        String          summary,
        String          errorCode,
        LocalDateTime   createdAt
) {}
