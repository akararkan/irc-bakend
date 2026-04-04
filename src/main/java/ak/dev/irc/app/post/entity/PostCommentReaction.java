package ak.dev.irc.app.post.entity;


import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "post_comment_reactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostCommentReaction {

    @EmbeddedId
    private PostCommentReactionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("commentId")
    @JoinColumn(name = "comment_id")
    private PostComment comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false)
    private PostReactionType reactionType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
