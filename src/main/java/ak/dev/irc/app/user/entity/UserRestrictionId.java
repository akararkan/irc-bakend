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
public class UserRestrictionId implements Serializable {

    @Column(name = "restrictor_id", nullable = false)
    private UUID restrictorId;

    @Column(name = "restricted_id", nullable = false)
    private UUID restrictedId;
}
