package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the authenticated user does not have permission for the action.
 * <p>Always maps to HTTP 403 Forbidden.</p>
 */
public class ForbiddenException extends AppException {

    public ForbiddenException(String message) {
        super(message, HttpStatus.FORBIDDEN, "ACCESS_FORBIDDEN");
    }

    public ForbiddenException(String message, String errorCode) {
        super(message, HttpStatus.FORBIDDEN, errorCode);
    }
}
