package ak.dev.irc.app.user.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_blocks",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_blocks",
        columnNames = {"blocker_id", "blocked_id"}
    ),
    indexes = {
        @Index(name = "idx_block_blocker", columnList = "blocker_id"),
        @Index(name = "idx_block_blocked", columnList = "blocked_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBlock extends BaseAuditEntity {

    @EmbeddedId
    private UserBlockId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("blockerId")
    @JoinColumn(name = "blocker_id",
                foreignKey = @ForeignKey(name = "fk_block_blocker"))
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("blockedId")
    @JoinColumn(name = "blocked_id",
                foreignKey = @ForeignKey(name = "fk_block_blocked"))
    private User blocked;

    @Column(name = "blocked_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime blockedAt = LocalDateTime.now();
}
