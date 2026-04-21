package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.PostReactionType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class CommentResponse {
    private UUID id;
    private UUID postId;
    private UUID parentId;

    private PostResponse.AuthorSummary author;

    // ── content ───────────────────────────────────────────────
    private String textContent;

    // ── media attachment (image / video) ──────────────────────
    private String mediaUrl;
    private String mediaType;
    private String mediaThumbnailUrl;

    // (voice comments removed for posts)

    // ── reactions ─────────────────────────────────────────────
    private Long reactionCount;
    private Long replyCount;
    private PostReactionType myReaction;

    private boolean edited;
    private LocalDateTime editedAt;
    private boolean deleted;
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
