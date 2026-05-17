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

    // ── Counts ────────────────────────────────────────────────────────────────
    long            viewCount,
    ViewBreakdown   viewBreakdown,   // only populated for the story author
    long            reactionCount,
    long            replyCount,

    // ── Viewer-specific state ─────────────────────────────────────────────────
    String          myReactionEmoji, // null = viewer has not reacted
    boolean         isSeen,          // true if viewer has already seen this story

    LocalDateTime   createdAt
) {
    public record StoryAuthor(
        UUID   id,
        String username,
        String fullName,
        String avatarUrl,
        String role,
        String accountType
    ) {}

    public record SoundBrief(
        UUID   id,
        String title,
        String artistName,
        String audioUrl,
        String coverArtUrl,
        int    clipStartSeconds,
        float  volume
    ) {}

    /**
     * View count segmented by the viewer's relationship to the author.
     * Only returned when the authenticated user is the story author.
     * Null for all other viewers.
     */
    public record ViewBreakdown(
        long total,
        long byCloseFriends,
        long byFollowers,
        long byPublic,
        long byAuthor       // self-views (for completeness, usually 0 or 1)
    ) {}
}
