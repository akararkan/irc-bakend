package ak.dev.irc.app.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * "Don't show me this person" — the explicit negative-feedback signal for
 * friend suggestions. A dismissed candidate is removed immediately and
 * excluded from every future recompute (platforms treat ignored/hidden
 * suggestions as one of the strongest negative signals).
 */
@Entity
@Table(name = "suggestion_dismissals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SuggestionDismissal {

    @EmbeddedId
    private Key id;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @EqualsAndHashCode
    @jakarta.persistence.Embeddable
    public static class Key implements Serializable {
        @Column(name = "user_id",      nullable = false) private UUID userId;
        @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    }
}
