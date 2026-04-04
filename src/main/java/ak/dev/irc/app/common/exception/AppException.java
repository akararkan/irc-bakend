package ak.dev.irc.app.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.Map;

/**
 * The main business exception for the entire application.
 * <p>
 * Carries an {@link HttpStatus}, a machine-readable {@code errorCode}, and
 * an optional {@code details} map so every throw site can provide rich
 * context that lands in the API error response unchanged.
 * </p>
 *
 * <h3>Usage examples</h3>
 * <pre>
 *   // Simple
 *   throw new AppException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
 *
 *   // With details
 *   throw new AppException(
 *       "Email already registered",
 *       HttpStatus.CONFLICT,
 *       "EMAIL_DUPLICATE",
 *       Map.of("email", email)
 *   );
 * </pre>
 */
@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> details;

    public AppException(String message, HttpStatus status) {
        super(message);
        this.status    = status;
        this.errorCode = null;
        this.details   = Collections.emptyMap();
    }
    public AppException(String message, HttpStatus status, String errorCode,
                        Throwable cause, Map<String, Object> details) {
        super(message, cause);
        this.status    = status;
        this.errorCode = errorCode;
        this.details   = details != null ? details : Collections.emptyMap();
    }

    public AppException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status    = status;
        this.errorCode = errorCode;
        this.details   = Collections.emptyMap();
    }

    public AppException(String message, HttpStatus status, String errorCode,
                        Map<String, Object> details) {
        super(message);
        this.status    = status;
        this.errorCode = errorCode;
        this.details   = details != null ? details : Collections.emptyMap();
    }

    public AppException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status    = status;
        this.errorCode = null;
        this.details   = Collections.emptyMap();
    }

    public AppException(String message, HttpStatus status, String errorCode,
                        Throwable cause) {
        super(message, cause);
        this.status    = status;
        this.errorCode = errorCode;
        this.details   = Collections.emptyMap();
    }
}
