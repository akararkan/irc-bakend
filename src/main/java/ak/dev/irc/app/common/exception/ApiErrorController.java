package ak.dev.irc.app.common.exception;

import ak.dev.irc.app.common.dto.ApiErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Unifies the servlet container's {@code /error} dispatch with the rest of the
 * API. By default Spring Boot's {@code BasicErrorController} answers JSON clients
 * with {@code {timestamp, status, error, message, path}} — a different envelope
 * from {@link GlobalExceptionHandler}'s {@link ApiErrorResponse}. That mismatch is
 * what the frontend saw as "QnA/Research return a different error shape".
 *
 * <p>This controller replaces it so <em>every</em> error — including ones raised
 * in a filter or by the container before the {@code @RestControllerAdvice} can run
 * — comes back in the exact same {@link ApiErrorResponse} shape.</p>
 *
 * <p>Errors that reach the {@code @RestControllerAdvice} never get here; this only
 * catches the container-level forwards that would otherwise bypass it.</p>
 */
@Slf4j
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiErrorResponse> handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        String traceId = UUID.randomUUID().toString();

        Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = originalPath != null ? originalPath.toString() : request.getRequestURI();

        log.warn("[{}] Container-level error dispatch — status={} path={}",
                traceId, status.value(), path);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(messageFor(status))
                .path(path)
                .errorCode(errorCodeFor(status))
                .traceId(traceId)
                .build();

        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus resolved = HttpStatus.resolve(value);
            if (resolved != null) return resolved;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** Stable, non-leaky message per status — never echoes raw container text. */
    private static String messageFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "You must be authenticated to access this resource.";
            case FORBIDDEN    -> "You do not have permission to perform this action.";
            case NOT_FOUND    -> "The requested resource was not found.";
            default -> status.is5xxServerError()
                    ? "An unexpected error occurred. Please try again later."
                    : status.getReasonPhrase();
        };
    }

    private static String errorCodeFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "AUTH_REQUIRED";
            case FORBIDDEN    -> "ACCESS_DENIED";
            case NOT_FOUND    -> "ENDPOINT_NOT_FOUND";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "ERROR_" + status.value();
        };
    }
}
