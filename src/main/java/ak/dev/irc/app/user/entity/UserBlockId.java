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
public class UserBlockId implements Serializable {

    @Column(name = "blocker_id", nullable = false)
    private UUID blockerId;

    @Column(name = "blocked_id", nullable = false)
    private UUID blockedId;
}
