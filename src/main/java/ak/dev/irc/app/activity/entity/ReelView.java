package ak.dev.irc.app.activity.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "reel_views",
        indexes = {
                @Index(name = "idx_reelview_user_created", columnList = "user_id, created_at DESC"),
                @Index(name = "idx_reelview_post",         columnList = "post_id")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReelView extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_reelview_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_reelview_post"))
    private Post post;

    @Column(name = "watched_seconds")
    private Integer watchedSeconds;
}
