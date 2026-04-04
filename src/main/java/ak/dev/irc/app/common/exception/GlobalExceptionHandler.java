package ak.dev.irc.app.common.exception;

import ak.dev.irc.app.common.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                  GLOBAL EXCEPTION HANDLER                          │
 * │                                                                     │
 * │  Catches every exception the application can throw and converts    │
 * │  it into a consistent {@link ApiErrorResponse} JSON body.          │
 * │                                                                     │
 * │  Covered categories:                                               │
 * │   1. Business exceptions (AppException + subclasses)               │
 * │   2. Validation (@Valid failures, missing params, type mismatch)   │
 * │   3. Security (bad credentials, disabled, locked, expired, 403)    │
 * │   4. HTTP routing (405, 415, 404)                                  │
 * │   5. Database (unique constraint, FK violation)                    │
 * │   6. File upload (size exceeded)                                   │
 * │   7. Illegal argument / state                                      │
 * │   8. Catch-all (nothing leaks as raw stack trace)                  │
 * └─────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ══════════════════════════════════════════════════════════════════════════
    //  1. BUSINESS EXCEPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleAppException(
            AppException ex, HttpServletRequest request) {

        String traceId = traceId();
        HttpStatus status = ex.getStatus();

        log.warn("[{}] AppException on {} {} — status={}, errorCode={}, message='{}'",
                traceId, request.getMethod(), request.getRequestURI(),
                status.value(), ex.getErrorCode(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode(ex.getErrorCode())
                .details(ex.getDetails().isEmpty() ? null : ex.getDetails())
                .traceId(traceId)
                .build();

        return ResponseEntity.status(status).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  2. VALIDATION EXCEPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        String traceId = traceId();

        List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors().stream()
                .map(fe -> new ApiErrorResponse.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage(),
                        fe.getRejectedValue()))
                .toList();

        log.warn("[{}] Validation failed on {} {} — {} field error(s): {}",
                traceId, request.getMethod(), request.getRequestURI(),
                fieldErrors.size(), fieldErrors);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("One or more fields failed validation. Check 'fieldErrors' for details.")
                .path(request.getRequestURI())
                .errorCode("VALIDATION_FAILED")
                .fieldErrors(fieldErrors)
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Missing parameter '{}' on {} {}",
                traceId, ex.getParameterName(), request.getMethod(), request.getRequestURI());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(String.format("Required parameter '%s' of type '%s' is missing",
                        ex.getParameterName(), ex.getParameterType()))
                .path(request.getRequestURI())
                .errorCode("MISSING_PARAMETER")
                .details(Map.of("parameter", ex.getParameterName(),
                                "expectedType", ex.getParameterType()))
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String traceId = traceId();
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName() : "unknown";

        log.warn("[{}] Type mismatch for '{}' — expected '{}', got '{}' on {} {}",
                traceId, ex.getName(), requiredType, ex.getValue(),
                request.getMethod(), request.getRequestURI());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(String.format("Parameter '%s' must be of type '%s'. Received: '%s'",
                        ex.getName(), requiredType, ex.getValue()))
                .path(request.getRequestURI())
                .errorCode("TYPE_MISMATCH")
                .details(Map.of("parameter", ex.getName(),
                                "expectedType", requiredType,
                                "receivedValue", String.valueOf(ex.getValue())))
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Malformed request body on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("Malformed JSON request body. Please check your JSON syntax and field types.")
                .path(request.getRequestURI())
                .errorCode("MALFORMED_JSON")
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  3. SECURITY / AUTHENTICATION EXCEPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Access denied on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("You do not have permission to perform this action.")
                .path(request.getRequestURI())
                .errorCode("ACCESS_DENIED")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {

        String traceId = traceId();
        String errorCode;
        String message;

        if (ex instanceof BadCredentialsException) {
            errorCode = "AUTH_BAD_CREDENTIALS";
            message   = "Invalid email or password.";
        } else if (ex instanceof DisabledException) {
            errorCode = "AUTH_ACCOUNT_DISABLED";
            message   = "Your account is disabled. Please verify your email or contact support.";
        } else if (ex instanceof LockedException) {
            errorCode = "AUTH_ACCOUNT_LOCKED";
            message   = "Your account is locked. Please contact support.";
        } else if (ex instanceof AccountExpiredException) {
            errorCode = "AUTH_ACCOUNT_EXPIRED";
            message   = "Your account has expired. Please contact support.";
        } else if (ex instanceof CredentialsExpiredException) {
            errorCode = "AUTH_CREDENTIALS_EXPIRED";
            message   = "Your credentials have expired. Please reset your password.";
        } else if (ex instanceof InsufficientAuthenticationException) {
            errorCode = "AUTH_INSUFFICIENT";
            message   = "Full authentication is required to access this resource.";
        } else {
            errorCode = "AUTH_FAILED";
            message   = "Authentication failed: " + ex.getMessage();
        }

        log.warn("[{}] Authentication failure [{}] on {} {} — {}",
                traceId, errorCode, request.getMethod(), request.getRequestURI(), message);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message(message)
                .path(request.getRequestURI())
                .errorCode(errorCode)
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  4. HTTP / ROUTING EXCEPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Method '{}' not supported on {} — supported: {}",
                traceId, ex.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.METHOD_NOT_ALLOWED.value())
                .error("Method Not Allowed")
                .message(String.format("HTTP method '%s' is not supported for this endpoint. " +
                        "Supported: %s", ex.getMethod(), ex.getSupportedHttpMethods()))
                .path(request.getRequestURI())
                .errorCode("METHOD_NOT_ALLOWED")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Unsupported media type '{}' on {} {}",
                traceId, ex.getContentType(), request.getMethod(), request.getRequestURI());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value())
                .error("Unsupported Media Type")
                .message(String.format("Content type '%s' is not supported. Supported: %s",
                        ex.getContentType(), ex.getSupportedMediaTypes()))
                .path(request.getRequestURI())
                .errorCode("UNSUPPORTED_MEDIA_TYPE")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            Exception ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] No handler found for {} {}",
                traceId, request.getMethod(), request.getRequestURI());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(String.format("No endpoint found for %s %s",
                        request.getMethod(), request.getRequestURI()))
                .path(request.getRequestURI())
                .errorCode("ENDPOINT_NOT_FOUND")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  5. DATABASE EXCEPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        String traceId = traceId();
        String rootMsg = ex.getRootCause() != null
                ? ex.getRootCause().getMessage() : ex.getMessage();

        log.error("[{}] Data integrity violation on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), rootMsg);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message("A data integrity constraint was violated. " +
                         "This usually means a duplicate or invalid reference exists.")
                .path(request.getRequestURI())
                .errorCode("DATA_INTEGRITY_VIOLATION")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  6. FILE UPLOAD EXCEPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUpload(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] File upload size exceeded on {} {}",
                traceId, request.getMethod(), request.getRequestURI());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.PAYLOAD_TOO_LARGE.value())
                .error("Payload Too Large")
                .message("The uploaded file exceeds the maximum allowed size.")
                .path(request.getRequestURI())
                .errorCode("FILE_TOO_LARGE")
                .details(Map.of("maxSize", ex.getMaxUploadSize()))
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  7. ILLEGAL ARGUMENT / STATE
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Illegal argument on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("ILLEGAL_ARGUMENT")
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.error("[{}] Illegal state on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected state was encountered. Please try again or contact support.")
                .path(request.getRequestURI())
                .errorCode("ILLEGAL_STATE")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  8. CATCH-ALL
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAllUncaught(
            Exception ex, HttpServletRequest request) {

        String traceId = traceId();
        log.error("[{}] UNHANDLED exception on {} {} — {}: {}",
                traceId, request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage(), ex);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected error occurred. Please try again later. " +
                         "If the problem persists, contact support with trace ID: " + traceId)
                .path(request.getRequestURI())
                .errorCode("INTERNAL_ERROR")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════

    private String traceId() {
        return UUID.randomUUID().toString();
    }
}
