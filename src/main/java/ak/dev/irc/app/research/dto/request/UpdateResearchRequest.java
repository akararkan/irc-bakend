package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.research.enums.ResearchVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record UpdateResearchRequest(

    @Size(max = 500) String title,
    @Size(max = 50000) String description,
    @Size(max = 5000) String abstractText,
    @Size(max = 2000) String keywords,
    @Size(max = 5000) String citation,
    @Size(max = 255) String doi,

    ResearchVisibility visibility,
    LocalDateTime scheduledPublishAt,
    Boolean commentsEnabled,
    Boolean downloadsEnabled,

    @Size(max = 30) List<@Size(max = 100) String> tags,

    @Valid List<SourceRequest> sources
) {}
