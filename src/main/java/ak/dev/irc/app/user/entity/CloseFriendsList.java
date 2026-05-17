package ak.dev.irc.app.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "close_friends",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_cf_owner_friend",
        columnNames = {"owner_id", "friend_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloseFriendsList {

    @EmbeddedId
    private CloseFriendsId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("ownerId")
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("friendId")
    @JoinColumn(name = "friend_id")
    private User friend;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class CloseFriendsId implements Serializable {
        @Column(name = "owner_id",  nullable = false) private UUID ownerId;
        @Column(name = "friend_id", nullable = false) private UUID friendId;
    }
}
