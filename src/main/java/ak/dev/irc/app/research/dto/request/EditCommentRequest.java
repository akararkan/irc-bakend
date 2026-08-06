package ak.dev.irc.app.research.dto.request;

import ak.dev.irc.app.common.messages.ResearchMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditCommentRequest(

    @NotBlank(message = ResearchMessages.VAL_COMMENT_CONTENT_REQUIRED)
    @Size(max = 5000, message = ResearchMessages.VAL_COMMENT_MAX_5000)
    String content
) {}
