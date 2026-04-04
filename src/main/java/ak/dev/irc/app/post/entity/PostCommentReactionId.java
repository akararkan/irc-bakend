package ak.dev.irc.app.post.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
public class PostCommentReactionId implements Serializable {

    @Column(name = "comment_id")
    private UUID commentId;

    @Column(name = "user_id")
    private UUID userId;
}