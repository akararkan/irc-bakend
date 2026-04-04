package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a request is not authenticated or authentication has failed.
 * <p>Always maps to HTTP 401 Unauthorized.</p>
 */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED");
    }

    public UnauthorizedException(String message, String errorCode) {
        super(message, HttpStatus.UNAUTHORIZED, errorCode);
    }

    public UnauthorizedException(String message, String errorCode,
                                  Map<String, Object> details) {
        super(message, HttpStatus.UNAUTHORIZED, errorCode, details);
    }
}
