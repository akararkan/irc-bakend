package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One reaction by one user on a research comment. Mirrors
 * {@link ak.dev.irc.app.post.entity.PostCommentReaction} so research comments
 * have the same multi-reaction model as posts (LIKE, LOVE, INSIGHTFUL, etc.)
 * rather than a boolean like.
 *
 * <p>Mapped to the legacy {@code research_comment_likes} table — the
 * {@code reaction_type} column is added by Hibernate {@code ddl-auto=update}
 * on next boot. Existing rows have NULL reaction_type and are treated as
 * {@code LIKE} by the service layer for back-compat.</p>
 */
@Entity
@Table(name = "research_comment_likes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchCommentReaction {

    @EmbeddedId
    private ResearchCommentReactionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("commentId")
    @JoinColumn(name = "comment_id")
    private ResearchComment comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", length = 20)
    private ReactionType reactionType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
