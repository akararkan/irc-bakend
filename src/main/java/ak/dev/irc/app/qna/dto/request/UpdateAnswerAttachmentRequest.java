package ak.dev.irc.app.qna.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAnswerAttachmentRequest {

    @Size(max = 500, message = "Caption must not exceed 500 characters")
    private String caption;

    private Integer displayOrder;
}
