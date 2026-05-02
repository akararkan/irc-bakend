package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateAnswerRequest {

    @NotBlank(message = "Answer body is required")
    @Size(max = 10000, message = "Answer must not exceed 10000 characters")
    private String body;

    /** When set, this answer is a reanswer (reply) under the given parent answer. */
    private UUID parentAnswerId;

    // ── Media (photo or video) — kept for backward compatibility ──
    private String mediaUrl;
    private String mediaType;           // IMAGE, VIDEO
    private String mediaThumbnailUrl;

    // ── Voice recording ───────────────────────────────────────
    private String voiceUrl;
    private Integer voiceDurationSeconds;

    // ── Links (comma-separated URLs) ──────────────────────────
    private String links;

    // ── Sources / references ──────────────────────────────────
    @Valid
    private List<CreateAnswerSourceRequest> sources;
}
