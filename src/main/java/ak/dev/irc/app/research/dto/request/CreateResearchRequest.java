package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.common.messages.ResearchMessages;
import ak.dev.irc.app.common.text.BodyFormat;
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

        @NotBlank(message = ResearchMessages.VAL_TITLE_REQUIRED)
        @Size(max = 500, message = ResearchMessages.VAL_TITLE_MAX_500)
        String title,

        @NotBlank(message = ResearchMessages.VAL_DESCRIPTION_REQUIRED)
        @Size(max = 50000, message = ResearchMessages.VAL_DESCRIPTION_MAX_50000)
        String description,

        @NotBlank(message = ResearchMessages.VAL_ABSTRACT_REQUIRED)
        @Size(max = 5000, message = ResearchMessages.VAL_ABSTRACT_MAX_5000)
        String abstractText,

        /**
         * How {@link #description} and {@link #abstractText} should be parsed
         * before being rendered to sanitised HTML. Optional — when null, the
         * server auto-detects ({@code HTML} when the source contains obvious
         * tag patterns, otherwise {@code MARKDOWN}, which is a safe superset
         * of plain text). Sanitisation is always applied; scripts, inline
         * event handlers, and non-{@code http(s)} URL schemes are stripped.
         */
        BodyFormat bodyFormat,

        @Size(max = 2000, message = ResearchMessages.VAL_KEYWORDS_MAX_2000)
        String keywords,

        @Size(max = 5000, message = ResearchMessages.VAL_CITATION_MAX_5000)
        String citation,

        // ── Publication settings ──────────────────────────────────────────────

        ResearchVisibility visibility,

        /** Null → publish immediately when {@code publish} action is called. */
        LocalDateTime scheduledPublishAt,

        boolean commentsEnabled,

        boolean downloadsEnabled,

        // ── Tags ─────────────────────────────────────────────────────────────

        @NotEmpty(message = ResearchMessages.VAL_TAGS_REQUIRED)
        @Size(max = 30, message = ResearchMessages.VAL_TAGS_MAX_30)
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
        List<MediaUploadMetadata> mediaFiles,

        // ── Contributors ──────────────────────────────────────────────────────

        /**
         * Optional named participants other than the corresponding researcher
         * (co-authors, advisors, translators, etc.). Each referenced user must
         * already exist and carry role RESEARCHER or SCHOLAR.
         */
        @Valid
        List<ContributorRequest> contributors

) {
    /** Default visibility to PUBLIC if not explicitly set. */
    public CreateResearchRequest {
        if (visibility == null) visibility = ResearchVisibility.PUBLIC;
    }
}