package ak.dev.irc.app.post.dto.story;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoryViewerResponse(
    UUID          viewerId,
    String        viewerUsername,
    String        viewerAvatarUrl,
    Integer       watchDurationMs,
    String        reactionEmoji,
    boolean       replied,
    LocalDateTime viewedAt
) {}
