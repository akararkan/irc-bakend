package ak.dev.irc.app.moderation.image;

/**
 * One image's scores from the image-inference container: two probabilities
 * summing to 1. {@code nsfw} is the only one policy reads — {@code normal} is
 * carried for the ops panel.
 */
public record ImageScoreResult(double nsfw, double normal, String modelVersion,
                               double inferenceMs) {
}
