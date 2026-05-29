package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.common.text.BodyFormat;
import ak.dev.irc.app.research.enums.ResearchVisibility;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record UpdateResearchRequest(

    @JsonAlias({"name"})
    @Size(max = 500) String title,

    // {@code description} is the body; accept common aliases so a frontend
    // sending {@code body}/{@code text}/{@code content} doesn't silently no-op.
    @JsonAlias({"body", "text", "content"})
    @Size(max = 50000) String description,

    // Accept {@code abstract} (the natural JSON name) — it's a reserved word in
    // Java so the canonical record component has to be {@code abstractText}.
    @JsonAlias({"abstract", "summary"})
    @Size(max = 5000) String abstractText,
    /**
     * Optional — when non-null, the body and/or abstract on this update are
     * re-rendered under the new format. When null and {@code description} or
     * {@code abstractText} changed, the server keeps the row's existing
     * {@code bodyFormat} (or auto-detects if the row has none yet).
     */
    BodyFormat bodyFormat,
    @Size(max = 2000) String keywords,
    @Size(max = 5000) String citation,

    ResearchVisibility visibility,
    LocalDateTime scheduledPublishAt,
    Boolean commentsEnabled,
    Boolean downloadsEnabled,

    @Size(max = 30) List<@Size(max = 100) String> tags,

    @Valid List<SourceRequest> sources,

    /**
     * If non-null, REPLACES the full contributor list for this research
     * (PATCH semantics — pass the desired list, not a delta). Pass an empty
     * list to clear all contributors. Leave null to leave contributors
     * untouched. Each referenced user must be RESEARCHER or SCHOLAR.
     */
    @Valid List<ContributorRequest> contributors
) {}
