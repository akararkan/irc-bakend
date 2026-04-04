package ak.dev.irc.app.post.dto;


import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateCommentRequest {

    /** null = top-level comment; non-null = reply */
    private UUID parentId;

    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String textContent;

    // ── voice comment (the unique feature) ───────────────────
    private String voiceUrl;
    private Integer voiceDurationSeconds;
    private String voiceTranscript;
    private String waveformData;
}
