package ak.dev.irc.app.post.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
public class PostReactionId implements Serializable {

    @Column(name = "post_id")
    private UUID postId;

    @Column(name = "user_id")
    private UUID userId;
}