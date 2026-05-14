package ak.dev.irc.app.post.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent view ledger for posts (and reels — same table, since a reel is
 * just a {@code PostType.REEL}). One row per (post, user) pair, ever. The
 * service layer uses {@code INSERT ... ON CONFLICT DO NOTHING} so the second
 * (and Nth) view from the same user is a no-op at both the row and counter
 * level. This replaces the previous Redis 1h-window dedup, which let the same
 * user inflate the counter once per hour forever.
 *
 * <p>Anonymous viewers do <em>not</em> hit this table — there is no stable id
 * to key on. They keep the legacy Redis IP-fingerprint dedup with a 1h window.
 */
@Entity
@Table(name = "post_views", indexes = {
        @Index(name = "idx_post_view_user", columnList = "user_id"),
        @Index(name = "idx_post_view_post", columnList = "post_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostView {

    @EmbeddedId
    private PostViewId id;

    @CreationTimestamp
    @Column(name = "first_viewed_at", updatable = false, nullable = false)
    private LocalDateTime firstViewedAt;

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class PostViewId implements Serializable {
        @Column(name = "post_id", nullable = false)
        private UUID postId;
        @Column(name = "user_id", nullable = false)
        private UUID userId;
    }
}
