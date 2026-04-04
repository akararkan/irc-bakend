package ak.dev.irc.app.rabbitmq.event.post;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostCreatedEvent implements Serializable {
    private UUID postId;
    private UUID authorId;
    private String postType;
    private String visibility;
    private boolean hasVoice;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}