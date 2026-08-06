package ak.dev.irc.app.media.entity;

import ak.dev.irc.app.user.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-role daily upload quota (admin media-storage.md §8). Roles WITHOUT a
 * row (or with {@code enabled=false}) are unenforced — that is how ADMIN and
 * the staff tiers stay unlimited. Counted against upload intents since
 * midnight UTC, using declared original sizes.
 */
@Entity
@Table(name = "media_quotas")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaQuota {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "daily_uploads", nullable = false)
    private int dailyUploads;

    @Column(name = "daily_bytes", nullable = false)
    private long dailyBytes;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
