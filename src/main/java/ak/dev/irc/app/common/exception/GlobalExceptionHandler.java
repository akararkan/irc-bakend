package ak.dev.irc.app.common.exception;

import ak.dev.irc.app.common.dto.ApiErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
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
        String received = String.valueOf(ex.getValue());

        // Hot-path antipattern: the frontend templated `undefined` / `null`
        // into the URL because the JS variable wasn't hydrated before the
        // fetch. Same 400 + same errorCode, but the message points the
        // frontend developer straight at the bug.
        boolean jsLiteralUndefined = "undefined".equals(received) || "null".equals(received);

        log.warn("[{}] Type mismatch for '{}' — expected '{}', got '{}' on {} {}{}",
                traceId, ex.getName(), requiredType, received,
                request.getMethod(), request.getRequestURI(),
                jsLiteralUndefined ? " [LIKELY FRONTEND BUG — calling API before hydrating path param]" : "");

        String message = jsLiteralUndefined
                ? String.format("Parameter '%s' was the JS literal '%s' — the frontend templated an unhydrated " +
                                "variable into the URL. Guard the call site (e.g. `if (!%s) return;`) before fetching.",
                                ex.getName(), received, ex.getName())
                : String.format("Parameter '%s' must be of type '%s'. Received: '%s'",
                                ex.getName(), requiredType, received);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(message)
                .path(request.getRequestURI())
                .errorCode("TYPE_MISMATCH")
                .details(Map.of("parameter", ex.getName(),
                                "expectedType", requiredType,
                                "receivedValue", received,
                                "hint", jsLiteralUndefined
                                        ? "frontend_path_param_unhydrated"
                                        : "value_not_convertible"))
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

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            Exception ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Access denied on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        // SSE-only requests can't accept a JSON error body; return status-only
        // to avoid the HttpMediaTypeNotAcceptableException cascade. The
        // previous version of this branch set contentType(APPLICATION_JSON)
        // on the response builder which DOESN'T help — Spring's negotiation
        // is driven by the request's Accept header, not the response's
        // intended content-type. See isSseRequest() for the full rationale.
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

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

    /**
     * Plain {@link SecurityException} thrown by service-layer author/owner checks
     * (e.g. "Not the author" on story delete, poll attach, QnA/Research mutations).
     * Without this it falls through to the catch-all and surfaces as a misleading
     * {@code 500 INTERNAL_ERROR}; map it to {@code 403 FORBIDDEN} so the client can
     * distinguish "not allowed" from "server broke".
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurityException(
            SecurityException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Authorization failure on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message(ex.getMessage() != null && !ex.getMessage().isBlank()
                        ? ex.getMessage()
                        : "You do not have permission to perform this action.")
                .path(request.getRequestURI())
                .errorCode("FORBIDDEN")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
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

        // SSE-negotiated requests (Accept: text/event-stream) can't accept a
        // JSON body — trying to serialise ApiErrorResponse triggers
        // HttpMediaTypeNotAcceptableException which cascades through this
        // very handler and rains stack traces. Status-only response, no body.
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

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

    /**
     * Legacy JPA-style "not found" exception. Several services still throw
     * {@code jakarta.persistence.EntityNotFoundException} when an entity
     * lookup misses; without an explicit handler it leaks as a 500. Map it
     * to the same 404 shape the catalogue uses for {@link ResourceNotFoundException}.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Entity not found on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(ex.getMessage() != null ? ex.getMessage() : "Resource not found")
                .path(request.getRequestURI())
                .errorCode("RESOURCE_NOT_FOUND")
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
    //  8. EXTERNAL STORAGE (S3 / Cloudflare R2)
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(SdkClientException.class)
    public ResponseEntity<ApiErrorResponse> handleSdkClientException(
            SdkClientException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.error("[{}] Storage service (R2/S3) error on {} {} — {}: {}",
                traceId, request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage(), ex);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .error("Service Unavailable")
                .message("File storage service is currently unavailable. Please try again later.")
                .path(request.getRequestURI())
                .errorCode("STORAGE_UNAVAILABLE")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  9. CLIENT DISCONNECT (broken pipe during streaming responses)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The client (usually a browser <audio>/<video> element seeking, pausing,
     * or unmounting mid-stream) closed the TCP connection before we finished
     * writing. Nothing we can do — the response is already gone, and trying
     * to render an ApiErrorResponse on top of it produces the secondary
     * "No converter for [ApiErrorResponse] with preset Content-Type 'audio/mpeg'"
     * error. Log at DEBUG, return void → Spring treats the exception as handled.
     */
    @ExceptionHandler({
            ClientAbortException.class,
            AsyncRequestNotUsableException.class,
    })
    public void handleClientAbort(Exception ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("Client disconnected mid-response on {} {} — {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  9b. CONCURRENCY + DATASTORE INFRASTRUCTURE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * JPA optimistic-lock loss after {@code OptimisticLockRetry} exhausts its
     * attempts. This is a CONFLICT the client can resolve by re-reading and
     * retrying — not a server fault, so it must not land in the 500 catch-all.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Optimistic lock conflict on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message("The resource was modified concurrently. Please reload and retry.")
                .path(request.getRequestURI())
                .errorCode("OPTIMISTIC_LOCK_CONFLICT")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Transient datastore unavailability — Cassandra driver timeouts / no
     * reachable nodes, and Spring's resource-failure/query-timeout wrappers.
     * 503 tells clients (and load balancers) to retry, instead of a generic
     * 500 with a full stack logged for every hiccup.
     */
    @ExceptionHandler({
            com.datastax.oss.driver.api.core.DriverTimeoutException.class,
            com.datastax.oss.driver.api.core.AllNodesFailedException.class,
            org.springframework.dao.QueryTimeoutException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
    })
    public ResponseEntity<ApiErrorResponse> handleDatastoreUnavailable(
            Exception ex, HttpServletRequest request) {

        String traceId = traceId();
        log.error("[{}] Datastore unavailable on {} {} — {}: {}",
                traceId, request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage());

        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .error("Service Unavailable")
                .message("A backing datastore is temporarily unavailable. Please try again shortly.")
                .path(request.getRequestURI())
                .errorCode("DATASTORE_UNAVAILABLE")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Invalid CQL / schema drift — a server bug, but with a stable code instead of UNHANDLED noise. */
    @ExceptionHandler(com.datastax.oss.driver.api.core.servererrors.QueryValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleCqlValidation(
            com.datastax.oss.driver.api.core.servererrors.QueryValidationException ex,
            HttpServletRequest request) {

        String traceId = traceId();
        log.error("[{}] CQL validation failure on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("A datastore query failed. Please contact support with trace ID: " + traceId)
                .path(request.getRequestURI())
                .errorCode("DATASTORE_QUERY_ERROR")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Missing object key on R2/S3 — a NOT FOUND, not a 500. Must be declared
     * separately from (and is more specific than) the {@code S3Exception}
     * handler below; Spring picks the most specific match.
     */
    @ExceptionHandler(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleNoSuchKey(
            software.amazon.awssdk.services.s3.model.NoSuchKeyException ex,
            HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Storage object not found on {} {}",
                traceId, request.getMethod(), request.getRequestURI());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message("The requested file does not exist.")
                .path(request.getRequestURI())
                .errorCode("MEDIA_NOT_FOUND")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** Storage SERVICE errors (auth, throttling, 5xx from R2) — bad gateway, not a generic 500. */
    @ExceptionHandler(software.amazon.awssdk.services.s3.model.S3Exception.class)
    public ResponseEntity<ApiErrorResponse> handleS3Service(
            software.amazon.awssdk.services.s3.model.S3Exception ex, HttpServletRequest request) {

        String traceId = traceId();
        log.error("[{}] Storage service error on {} {} — status={} message={}",
                traceId, request.getMethod(), request.getRequestURI(),
                ex.statusCode(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message("File storage returned an error. Please try again later.")
                .path(request.getRequestURI())
                .errorCode("STORAGE_ERROR")
                .traceId(traceId)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  9c. REMAINING SPRING MVC EDGES (previously fell to the 500 catch-all)
    // ══════════════════════════════════════════════════════════════════════════

    /** {@code @Validated} method-parameter violations (path vars / query params). */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex, HttpServletRequest request) {

        String traceId = traceId();
        List<ApiErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new ApiErrorResponse.FieldError(
                        v.getPropertyPath() == null ? null : v.getPropertyPath().toString(),
                        v.getMessage(),
                        v.getInvalidValue()))
                .toList();

        log.warn("[{}] Constraint violation on {} {} — {} error(s)",
                traceId, request.getMethod(), request.getRequestURI(), fieldErrors.size());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("One or more parameters failed validation. Check 'fieldErrors' for details.")
                .path(request.getRequestURI())
                .errorCode("VALIDATION_FAILED")
                .fieldErrors(fieldErrors)
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    /** Spring 6.1 method-validation wrapper (`@Valid` on params without BindingResult). */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex,
            HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Handler method validation failed on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getReason());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("One or more request values failed validation.")
                .path(request.getRequestURI())
                .errorCode("VALIDATION_FAILED")
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    })
    public ResponseEntity<ApiErrorResponse> handleMissingRequestPiece(
            Exception ex, HttpServletRequest request) {

        String traceId = traceId();
        log.warn("[{}] Missing request piece on {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("MISSING_REQUEST_PART")
                .traceId(traceId)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Client demanded a representation we can't produce (e.g. Accept:
     * application/xml). A genuine 406 — previously landed in the catch-all as
     * an ERROR-logged 500. Body-less: writing JSON would re-trigger the same
     * negotiation failure.
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(
            org.springframework.web.HttpMediaTypeNotAcceptableException ex,
            HttpServletRequest request) {

        log.warn("Not-acceptable Accept header on {} {} — {}",
                request.getMethod(), request.getRequestURI(), request.getHeader("Accept"));
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  10. CATCH-ALL
    // ══════════════════════════════════════════════════════════════════════════

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAllUncaught(
            Exception ex, HttpServletRequest request, HttpServletResponse response) {

        // If the underlying cause is a client disconnect, treat it as #9.
        if (isClientDisconnect(ex)) {
            handleClientAbort(ex, request);
            return null;
        }

        // Response already started streaming (e.g. a half-written audio/video
        // body): we cannot append a JSON error body without colliding with
        // the existing Content-Type. Log and bail out.
        if (response.isCommitted()) {
            log.warn("Suppressing error body — response already committed on {} {} ({})",
                    request.getMethod(), request.getRequestURI(),
                    ex.getClass().getSimpleName());
            return null;
        }

        String traceId = traceId();
        log.error("[{}] UNHANDLED exception on {} {} — {}: {}",
                traceId, request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage(), ex);

        // Same SSE-content-negotiation guard as in handleNotFound — without
        // it, an unhandled exception on a stream endpoint cascades into a
        // HttpMediaTypeNotAcceptableException through this very method.
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

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

    /**
     * True when the client only accepts {@code text/event-stream} (the SSE
     * case). In that situation, returning any {@code ResponseEntity} with a
     * non-null body triggers {@code HttpMediaTypeNotAcceptableException}
     * because Spring's content negotiation has no JSON↔SSE converter to
     * fall back to — and that secondary exception cascades through this
     * very handler, producing the noisy double-stack pattern in the log.
     *
     * <p>Callers should branch on this and return a body-less
     * {@code ResponseEntity.status(...).build()} for SSE requests. The
     * client gets the right status code (which is all {@code EventSource.onerror}
     * needs to fire) and Spring writes no body, so content negotiation never
     * runs.</p>
     *
     * <p>{@code application/json} requests fall through to the normal
     * {@link ApiErrorResponse} body path. Browsers sending
     * {@code Accept: text/event-stream, * /*} also fall through (the {@code * /*}
     * lets Spring pick JSON cleanly).</p>
     */
    private static boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) return false;
        if (!accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) return false;
        // If */* (or any wildcard) is also acceptable, JSON works — only
        // treat as SSE-only when no wildcard fallback is offered.
        return !accept.contains("*/*") && !accept.contains("application/json");
    }

    // ══════════════════════════════════════════════════════════════════════════

    private String traceId() {
        // Prefer the request's existing correlation id (set by a filter into
        // MDC) so the error response joins up with access/audit logs; fall
        // back to a fresh id that is still self-correlating via our own log line.
        String mdc = org.slf4j.MDC.get("traceId");
        return (mdc != null && !mdc.isBlank()) ? mdc : UUID.randomUUID().toString();
    }

    /**
     * Walks the cause chain looking for a Tomcat/Jetty client-abort or any
     * IOException whose message looks like a broken pipe / connection reset.
     * These should never produce an error body.
     */
    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ClientAbortException
                    || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (t instanceof IOException) {
                String msg = t.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("broken pipe")
                            || lower.contains("connection reset")
                            || lower.contains("connection abort")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
