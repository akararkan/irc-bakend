package ak.dev.irc.app.activity.entity;

import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostComment;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "user_activities",
        indexes = {
                @Index(name = "idx_uact_user_created", columnList = "user_id, created_at DESC"),
                @Index(name = "idx_uact_user_type",    columnList = "user_id, activity_type"),
                @Index(name = "idx_uact_post",         columnList = "post_id"),
                @Index(name = "idx_uact_comment",      columnList = "comment_id")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserActivity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_uact_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 40)
    private UserActivityType activityType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id",
            foreignKey = @ForeignKey(name = "fk_uact_post"))
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id",
            foreignKey = @ForeignKey(name = "fk_uact_comment"))
    private PostComment comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", length = 30)
    private PostReactionType reactionType;

    @Column(name = "watched_seconds")
    private Integer watchedSeconds;
}
