package ak.dev.irc.app.rabbitmq.event.post;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostSharedEvent implements Serializable {
    private UUID postId;
    private UUID sharerId;
    private UUID postAuthorId;
    private String caption;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}