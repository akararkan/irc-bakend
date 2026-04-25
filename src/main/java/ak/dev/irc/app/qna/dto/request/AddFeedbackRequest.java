package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.qna.enums.FeedbackType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddFeedbackRequest {

    @NotNull(message = "Feedback type is required")
    private FeedbackType feedbackType;

    @Size(max = 5000, message = "Feedback body must not exceed 5000 characters")
    private String body;
}