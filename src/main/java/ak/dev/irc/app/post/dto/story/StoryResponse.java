package ak.dev.irc.app.post.dto.story;

import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoryResponse(
    UUID            id,
    StoryAuthor     author,
    StoryType       storyType,
    StoryVisibility visibility,
    String          textContent,
    String          backgroundType,
    String          backgroundValue,
    String          mediaUrl,
    String          thumbnailUrl,
    Integer         durationSeconds,
    UUID            linkedContentId,
    String          linkedContentSnapshot,
    String          overlaysJson,
    SoundBrief      sound,
    LocalDateTime   expiresAt,
    boolean         isExpired,
    long            viewCount,
    long            reactionCount,
    long            replyCount,
    String          myReactionEmoji,       // null = not reacted
    boolean         isSeen,               // true if viewer has already seen this story
    LocalDateTime   createdAt
) {
    public record StoryAuthor(UUID id, String username, String fullName, String avatarUrl, String role) {}
    public record SoundBrief(UUID id, String title, String artistName, String audioUrl, String coverArtUrl, int clipStartSeconds, float volume) {}
}
