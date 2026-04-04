package ak.dev.irc.app.research.entity;

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
public class ResearchReactionId implements Serializable {

    @Column(name = "research_id", nullable = false)
    private UUID researchId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
}
