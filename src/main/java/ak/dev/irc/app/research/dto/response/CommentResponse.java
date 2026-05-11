package ak.dev.irc.app.research.dto.response;

import ak.dev.irc.app.research.enums.ReactionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CommentResponse(
    UUID id,
    UUID researchId,

    UUID userId,
    String userFullName,
    String userUsername,
    String userProfileImage,

    String content,

    // ── media attachment (image / video) ──────────────────────
    String mediaUrl,
    String mediaType,
    String mediaThumbnailUrl,

    /** Denormalised total reactions across all reaction types (kept named likeCount for wire-format compatibility). */
    Long likeCount,
    Long replyCount,
    /** Viewer's reaction on this comment, or null if not reacted. Mirrors posts' {@code myReaction}. */
    ReactionType myReaction,
    boolean isEdited,
    LocalDateTime editedAt,
    boolean isHidden,
    LocalDateTime hiddenAt,

    UUID parentId,
    List<CommentResponse> replies,

    LocalDateTime createdAt,
    String timeAgo,
    String formattedDate
) {}
