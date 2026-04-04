package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when there is a conflict with the current state of the resource.
 * <p>Typically used for optimistic locking failures, concurrent modifications,
 * or version conflicts. Always maps to HTTP 409 Conflict.</p>
 *
 * <pre>
 *   throw new ConflictException("Research was modified by another user");
 *   // → HTTP 409 with error code "RESOURCE_CONFLICT"
 * </pre>
 */
public class ConflictException extends AppException {

    public ConflictException(String message) {
        super(message, HttpStatus.CONFLICT, "RESOURCE_CONFLICT");
    }

    public ConflictException(String message, String errorCode) {
        super(message, HttpStatus.CONFLICT, errorCode);
    }

    public ConflictException(String message, String errorCode, Map<String, Object> details) {
        super(message, HttpStatus.CONFLICT, errorCode, details);
    }
}