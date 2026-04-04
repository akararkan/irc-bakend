package ak.dev.irc.app.post.dto;


import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.enums.PostVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreatePostRequest {

    @NotNull(message = "Post type is required")
    private PostType postType;

    @Size(max = 5000, message = "Text content must not exceed 5000 characters")
    private String textContent;

    private PostVisibility visibility = PostVisibility.PUBLIC;

    // ── voice / audio ─────────────────────────────────────────
    /** CDN URL of the uploaded voice recording */
    private String voiceUrl;
    private Integer voiceDurationSeconds;
    private String voiceTranscript;
    private String waveformData;

    // ── background audio (reels / embedded) ──────────────────
    private String audioTrackUrl;
    private String audioTrackName;

    // ── media attachments ─────────────────────────────────────
    private List<MediaItemRequest> mediaList;

    // ── location ──────────────────────────────────────────────
    private String locationName;
    private Double locationLat;
    private Double locationLng;

    // ── share ─────────────────────────────────────────────────
    /** ID of the post being shared (optional) */
    private String sharedPostId;

    /** Optional caption when sharing */
    private String shareCaption;
}
