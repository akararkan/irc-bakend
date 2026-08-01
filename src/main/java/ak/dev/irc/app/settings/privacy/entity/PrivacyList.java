package ak.dev.irc.app.settings.privacy.entity;

import ak.dev.irc.app.settings.privacy.enums.PrivacyListType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A named custom audience (spec §5). {@link VisibilityLevel#CUSTOM} policies
 * resolve against membership in one of the owner's custom lists.
 */
@Entity
@Table(name = "privacy_lists",
       indexes = @Index(name = "idx_privacy_list_owner", columnList = "owner_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrivacyList {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private PrivacyListType type = PrivacyListType.CUSTOM;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
