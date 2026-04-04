package ak.dev.irc.app.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Unified API error response returned by every exception handler.
 * <p>
 * Every error your API returns — whether it's a validation failure, a JWT
 * expiry, a 404, or an unexpected 500 — will come back in this exact shape
 * so your frontend can always parse the same structure.
 * </p>
 *
 * <pre>
 * {
 *   "timestamp":  "2026-03-22T14:30:00",
 *   "status":     401,
 *   "error":      "Unauthorized",
 *   "message":    "JWT token has expired",
 *   "path":       "/api/v1/users/me",
 *   "errorCode":  "AUTH_TOKEN_EXPIRED",
 *   "details":    { "expiredAt": "2026-03-22T13:00:00" },
 *   "fieldErrors": null,
 *   "traceId":    "a1b2c3d4-e5f6-..."
 * }
 * </pre>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /** HTTP status code (e.g. 400, 401, 404, 500) */
    private int status;

    /** HTTP reason phrase (e.g. "Bad Request", "Not Found") */
    private String error;

    /** Human-readable explanation of what went wrong */
    private String message;

    /** The request URI that caused the error */
    private String path;

    /** Machine-readable error code for frontend switch/case handling */
    private String errorCode;

    /** Extra contextual details (varies per error type) */
    private Map<String, Object> details;

    /** Per-field validation errors (only for validation failures) */
    private List<FieldError> fieldErrors;

    /** Correlation ID for log tracing */
    private String traceId;

    @Getter
    public static class FieldError {
        private final String field;
        private final String message;
        private final Object rejectedValue;

        public FieldError(String field, String message, Object rejectedValue) {
            this.field = field;
            this.message = message;
            this.rejectedValue = rejectedValue;
        }
    }
}
