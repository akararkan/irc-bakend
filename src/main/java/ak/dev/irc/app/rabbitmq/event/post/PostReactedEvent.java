package ak.dev.irc.app.rabbitmq.event.post;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostReactedEvent implements Serializable {
    private UUID postId;
    private UUID reactorId;
    private UUID postAuthorId;
    private String reactionType;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}