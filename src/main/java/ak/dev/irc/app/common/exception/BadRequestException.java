package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a request contains invalid data that fails business rules.
 * <p>Always maps to HTTP 400 Bad Request.</p>
 */
public class BadRequestException extends AppException {

    public BadRequestException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    public BadRequestException(String message, String errorCode) {
        super(message, HttpStatus.BAD_REQUEST, errorCode);
    }

    public BadRequestException(String message, String errorCode,
                                Map<String, Object> details) {
        super(message, HttpStatus.BAD_REQUEST, errorCode, details);
    }
}
