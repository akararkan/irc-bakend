package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_follows",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_follows",
        columnNames = {"follower_id", "following_id"}
    ),
    indexes = {
        @Index(name = "idx_follow_follower",  columnList = "follower_id"),
        @Index(name = "idx_follow_following", columnList = "following_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserFollow extends BaseAuditEntity {

    @EmbeddedId
    private UserFollowId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("followerId")
    @JoinColumn(name = "follower_id",
                foreignKey = @ForeignKey(name = "fk_follow_follower"))
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("followingId")
    @JoinColumn(name = "following_id",
                foreignKey = @ForeignKey(name = "fk_follow_following"))
    private User following;

    @Column(name = "followed_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime followedAt = LocalDateTime.now();
}
