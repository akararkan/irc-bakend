package ak.dev.irc.app.moderation.client;

/**
 * The inference container did not answer in time, refused, or the circuit
 * breaker is open.
 *
 * <p>This is deliberately NOT an {@code AppException}: it must never reach the
 * user as an HTTP error. MODERATION_ROADMAP.md §7.4 is explicit that "the model
 * didn't answer" and "the model said it's clean" have to be distinguishable —
 * so the gateway catches this and applies the configured fallback policy (§5.6)
 * instead of silently approving.</p>
 */
public class InferenceUnavailableException extends RuntimeException {

    public InferenceUnavailableException(String message) {
        super(message);
    }

    public InferenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
