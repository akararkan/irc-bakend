package ak.dev.irc.app.research.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AddCommentRequest(

    @Size(max = 5000, message = "Comment must not exceed 5 000 characters")
    String content,

    /** Null for top-level comments; set for replies */
    UUID parentId,

    // ── media attachment (image / video) ──────────────────────
    String mediaUrl,
    String mediaType,          // IMAGE or VIDEO
    String mediaThumbnailUrl,

    // ── voice comment ─────────────────────────────────────────
    String voiceUrl,
    Integer voiceDurationSeconds,
    String voiceTranscript,
    String waveformData
) {}
