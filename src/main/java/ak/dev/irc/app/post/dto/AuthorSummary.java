package ak.dev.irc.app.post.dto;

import java.util.UUID;

/**
 * Lightweight projection of a post's author. Embedded into every post
 * response so the frontend never has to call {@code /users/{id}} just
 * to render a name/avatar.
 */
public record AuthorSummary(
        UUID   id,
        String username,
        String fullName,
        String profileImage
) {}
