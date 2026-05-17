package ak.dev.irc.app.rabbitmq.event.post;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StoryPollVotedEvent implements Serializable {
    private UUID   storyId;
    private UUID   pollId;
    private UUID   voterId;
    private String choice;
    private int    voteACount;
    private int    voteBCount;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
