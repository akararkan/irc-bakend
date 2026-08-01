package ak.dev.irc.app.settings.privacy.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Membership edge for a {@link PrivacyList}. Composite key {@code (listId, memberId)}.
 */
@Entity
@Table(name = "privacy_list_members",
       indexes = @Index(name = "idx_privacy_member_member", columnList = "member_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrivacyListMember {

    @EmbeddedId
    private PrivacyListMemberId id;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = LocalDateTime.now();
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @EqualsAndHashCode
    public static class PrivacyListMemberId implements Serializable {
        @Column(name = "list_id", nullable = false)
        private UUID listId;
        @Column(name = "member_id", nullable = false)
        private UUID memberId;
    }
}
