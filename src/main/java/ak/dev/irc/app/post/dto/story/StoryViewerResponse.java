package ak.dev.irc.app.post.dto.story;

import ak.dev.irc.app.post.enums.ViewerRelationship;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoryViewerResponse(
    UUID               viewerId,
    String             viewerUsername,
    String             viewerAvatarUrl,
    ViewerRelationship viewerRelationship,  // CLOSE_FRIEND | FOLLOWER | PUBLIC | AUTHOR
    Integer            watchDurationMs,
    String             reactionEmoji,
    boolean            replied,
    LocalDateTime      viewedAt
) {}
