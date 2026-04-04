package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Thrown when a create/update would violate a uniqueness constraint.
 * <p>Always maps to HTTP 409 Conflict.</p>
 *
 * <pre>
 *   throw new DuplicateResourceException("User", "email", email);
 *   // → "User already exists with email: john@example.com"
 * </pre>
 */
public class DuplicateResourceException extends AppException {

    public DuplicateResourceException(String resource, String field, Object value) {
        super(
            String.format("%s already exists with %s: %s", resource, field, value),
            HttpStatus.CONFLICT,
            resource.toUpperCase().replace(" ", "_") + "_DUPLICATE",
            Map.of("resource", resource, "field", field, "value", String.valueOf(value))
        );
    }

    public DuplicateResourceException(String message) {
        super(message, HttpStatus.CONFLICT, "RESOURCE_DUPLICATE");
    }
}
