package ak.dev.irc.app.rabbitmq.event.post;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/** Uploader-facing moderation outcome for a rejected sound upload. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SoundRejectedEvent implements Serializable {
    private UUID   soundId;
    private UUID   uploaderId;
    private String soundTitle;
    private String reason;
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
