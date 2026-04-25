package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.*;
import ak.dev.irc.app.post.dto.MediaItemResponse;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PostResponse {

    private UUID id;
    private AuthorSummary author;

    // ── content ───────────────────────────────────────────────
    private String textContent;
    private PostType postType;
    private PostStatus status;
    private PostVisibility visibility;


    // ── audio track ───────────────────────────────────────────
    private String audioTrackUrl;
    private String audioTrackName;

    // ── media ─────────────────────────────────────────────────
    private List<MediaItemResponse> mediaList;

    // ── location ──────────────────────────────────────────────
    private String locationName;
    private Double locationLat;
    private Double locationLng;

    // ── sharing ───────────────────────────────────────────────
    private PostResponse sharedPost;
    private String shareLink;

    // ── counters ──────────────────────────────────────────────
    private Long reactionCount;
    private Long commentCount;
    private Long shareCount;
    private Long viewCount;

    // ── current user context ──────────────────────────────────
    private PostReactionType myReaction;   // null if not reacted
    private boolean isSaved;

    // ── audit ─────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── inner summaries ───────────────────────────────────────
    @Data @Builder
    public static class AuthorSummary {
        private UUID id;
        private String username;
        private String fullName;
        private String avatarUrl;
        private String role;
    }
}
