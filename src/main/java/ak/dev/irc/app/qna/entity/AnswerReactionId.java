package ak.dev.irc.app.qna.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AnswerReactionId implements Serializable {

    @Column(name = "answer_id")
    private UUID answerId;

    @Column(name = "user_id")
    private UUID userId;
}
