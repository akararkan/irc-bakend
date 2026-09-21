package ak.dev.irc.app.moderation.image;

/**
 * The scorer answered 4xx: <em>this input is not a decodable image</em>. This is
 * deliberately a different type from {@code InferenceUnavailableException} —
 * "the file is corrupt" must never be mistaken for "the scorer is down". The
 * gate lets an unscorable file continue to the media pipeline, whose decoders
 * are the final arbiter and will reject it with the proper user-facing error;
 * the circuit breaker is never tripped for it.
 */
public class ImageUnscorableException extends RuntimeException {

    public ImageUnscorableException(String message) {
        super(message);
    }
}
