package ak.dev.irc.app.rabbitmq.event.post;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SoundApprovedEvent implements Serializable {
    private UUID   soundId;
    private UUID   uploaderId;
    private String soundTitle;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
