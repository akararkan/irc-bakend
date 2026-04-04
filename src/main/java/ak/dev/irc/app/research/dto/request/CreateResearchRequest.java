package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.research.enums.ResearchVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Payload for creating a new research publication.
 *
 * <p>Sent as the {@code "data"} part of a {@code multipart/form-data} request.
 * Actual binary files are sent as separate {@code "files[]"} parts.
 *
 * <p>Media metadata ({@link #mediaFiles()}) is matched to uploaded binary
 * files by position index — {@code files[0]} → {@code mediaFiles[0]}, etc.
 * Metadata entries beyond the number of uploaded files are ignored; files
 * without a matching metadata entry receive sensible defaults.
 *
 * <h3>Required fields</h3>
 * <ul>
 *   <li>{@code title}</li>
 *   <li>{@code description}</li>
 *   <li>{@code abstractText}</li>
 *   <li>{@code tags} — at least one tag, max 30</li>
 * </ul>
 */
public record CreateResearchRequest(

        // ── Core ─────────────────────────────────────────────────────────────

        @NotBlank(message = "Title is required")
        @Size(max = 500, message = "Title must not exceed 500 characters")
        String title,

        @NotBlank(message = "Description is required")
        @Size(max = 50000, message = "Description must not exceed 50 000 characters")
        String description,

        @NotBlank(message = "Abstract is required")
        @Size(max = 5000, message = "Abstract must not exceed 5 000 characters")
        String abstractText,

        @Size(max = 2000, message = "Keywords must not exceed 2 000 characters")
        String keywords,

        @Size(max = 5000, message = "Citation must not exceed 5 000 characters")
        String citation,

        @Size(max = 255, message = "DOI must not exceed 255 characters")
        String doi,

        // ── Publication settings ──────────────────────────────────────────────

        ResearchVisibility visibility,

        /** Null → publish immediately when {@code publish} action is called. */
        LocalDateTime scheduledPublishAt,

        boolean commentsEnabled,

        boolean downloadsEnabled,

        // ── Tags ─────────────────────────────────────────────────────────────

        @NotEmpty(message = "At least one tag is required")
        @Size(max = 30, message = "Maximum 30 tags allowed")
        List<@NotBlank @Size(max = 100) String> tags,

        // ── Inline sources ────────────────────────────────────────────────────

        @Valid
        List<SourceRequest> sources,

        // ── Media file metadata ───────────────────────────────────────────────

        /**
         * Optional metadata (caption, alt text, display order) for each binary
         * file uploaded in the {@code "files[]"} multipart parts.
         * Matched to files by index — may be null or shorter than the file list.
         */
        @Valid
        List<MediaUploadMetadata> mediaFiles

) {
    /** Default visibility to PUBLIC if not explicitly set. */
    public CreateResearchRequest {
        if (visibility == null) visibility = ResearchVisibility.PUBLIC;
    }
}