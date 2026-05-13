package ak.dev.irc.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 429 Too Many Requests — thrown by {@code RateLimiter} when an actor exceeds
 * the per-action burst for a given window.
 */
public class RateLimitExceededException extends AppException {

    public RateLimitExceededException(String action, long retryAfterSeconds) {
        super("Too many " + action + " requests — please slow down",
              HttpStatus.TOO_MANY_REQUESTS,
              "RATE_LIMITED",
              Map.of("action", action, "retryAfterSeconds", retryAfterSeconds));
    }
}
