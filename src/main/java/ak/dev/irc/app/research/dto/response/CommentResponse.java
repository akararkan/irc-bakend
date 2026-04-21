package ak.dev.irc.app.research.dto.response;

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



    Long likeCount,
    Long replyCount,
    boolean isEdited,
    LocalDateTime editedAt,
    boolean isHidden,
    LocalDateTime hiddenAt,

    UUID parentId,
    List<CommentResponse> replies,

    LocalDateTime createdAt
) {}
