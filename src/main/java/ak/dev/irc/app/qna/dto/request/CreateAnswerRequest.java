package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateAnswerRequest {

    @NotBlank(message = "Answer body is required")
    @Size(max = 10000, message = "Answer must not exceed 10000 characters")
    private String body;

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
