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

    // ── voice comment ─────────────────────────────────────────
    private String voiceUrl;
    private Integer voiceDurationSeconds;
    private String voiceTranscript;
    private String waveformData;

    // ── reactions ─────────────────────────────────────────────
    private Long reactionCount;
    private Long replyCount;
    private PostReactionType myReaction;

    private boolean deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
