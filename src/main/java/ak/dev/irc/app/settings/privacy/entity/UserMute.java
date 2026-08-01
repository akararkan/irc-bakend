package ak.dev.irc.app.settings.privacy.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One-directional, silent mute (spec §13). The muted user is unaware; their
 * content is filtered from the muter's feed while messaging still works. Unlike
 * a block this is not symmetric and severs nothing.
 */
@Entity
@Table(name = "user_mutes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMute {

    @EmbeddedId
    private UserMuteId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @EqualsAndHashCode
    public static class UserMuteId implements Serializable {
        @Column(name = "muter_id", nullable = false)
        private UUID muterId;
        @Column(name = "muted_id", nullable = false)
        private UUID mutedId;
    }
}
