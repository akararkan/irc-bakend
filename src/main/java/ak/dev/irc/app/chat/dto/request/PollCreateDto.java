package ak.dev.irc.app.chat.dto.request;

import ak.dev.irc.app.common.messages.ChatMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** The poll attached to a {@code POLL} message (Telegram poll/quiz). */
@Data
public class PollCreateDto {

    @NotBlank(message = ChatMessages.VAL_POLL_QUESTION_REQUIRED)
    @Size(max = 300)
    private String question;

    /** 2–10 answer options, in display order. */
    @NotEmpty
    @Size(min = 2, max = 10, message = ChatMessages.VAL_POLL_OPTIONS_RANGE)
    private List<@NotBlank @Size(max = 100) String> options;

    /** Voters may pick more than one option (not allowed for quizzes). */
    private boolean allowsMultipleAnswers;

    /** Anonymous polls hide who voted (only counts are shown). */
    private boolean anonymous = true;

    /** Quiz mode — exactly one correct option, revealed after voting. */
    private boolean quiz;

    /** Index into {@code options} of the correct answer (quiz only). */
    private Integer correctOptionIndex;

    /** Shown to quiz voters after they answer. */
    @Size(max = 200)
    private String explanation;
}
