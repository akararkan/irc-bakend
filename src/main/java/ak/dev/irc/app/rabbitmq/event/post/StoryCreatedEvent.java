package ak.dev.irc.app.rabbitmq.event.post;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published when a user posts a new story.
 * The RabbitMQ consumer uses this to notify followers/close-friends
 * via the normal in-app notification pipeline.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StoryCreatedEvent implements Serializable {
    private UUID   storyId;
    private UUID   authorId;
    private String authorUsername;
    private String storyType;
    private String visibility;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
