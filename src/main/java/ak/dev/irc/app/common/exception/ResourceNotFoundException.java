package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a requested resource does not exist.
 * <p>Always maps to HTTP 404.</p>
 *
 * <pre>
 *   throw new ResourceNotFoundException("User", "id", userId);
 *   // → "User not found with id: 550e8400-..."
 * </pre>
 */
public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException(String resource, String field, Object value) {
        super(
            String.format("%s not found with %s: %s", resource, field, value),
            HttpStatus.NOT_FOUND,
            resource.toUpperCase().replace(" ", "_") + "_NOT_FOUND",
            Map.of("resource", resource, "field", field, "value", String.valueOf(value))
        );
    }

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
