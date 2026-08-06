package ak.dev.irc.app.qna.dto.request;

import ak.dev.irc.app.common.messages.QnaMessages;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAnswerAttachmentRequest {

    @Size(max = 500, message = QnaMessages.VAL_ATTACHMENT_CAPTION_MAX)
    private String caption;

    private Integer displayOrder;
}
