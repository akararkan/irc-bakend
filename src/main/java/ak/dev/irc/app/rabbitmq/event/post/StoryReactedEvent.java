package ak.dev.irc.app.rabbitmq.event.post;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StoryReactedEvent implements Serializable {
    private UUID   storyId;
    private UUID   storyAuthorId;
    private UUID   reactorId;
    private String emoji;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
