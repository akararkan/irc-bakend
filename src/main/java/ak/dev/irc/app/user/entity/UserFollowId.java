package ak.dev.irc.app.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserFollowId implements Serializable {

    @Column(name = "follower_id", nullable = false)
    private UUID followerId;

    @Column(name = "following_id", nullable = false)
    private UUID followingId;
}
