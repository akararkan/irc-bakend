package ak.dev.irc.app.post.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "story_views",
    indexes = {
        @Index(name = "idx_sv_story",  columnList = "story_id"),
        @Index(name = "idx_sv_viewer", columnList = "viewer_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryView {

    @EmbeddedId
    private StoryViewId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("storyId")
    @JoinColumn(name = "story_id")
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("viewerId")
    @JoinColumn(name = "viewer_id")
    private ak.dev.irc.app.user.entity.User viewer;

    /** Milliseconds the viewer watched — engagement signal. */
    @Column(name = "watch_duration_ms")
    private Integer watchDurationMs;

    /** Emoji reaction — at most one per viewer per story. */
    @Column(name = "reaction_emoji", length = 10)
    private String reactionEmoji;

    @Column(name = "replied", nullable = false)
    @Builder.Default
    private boolean replied = false;

    @CreationTimestamp
    @Column(name = "viewed_at", updatable = false, nullable = false)
    private LocalDateTime viewedAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class StoryViewId implements Serializable {
        @Column(name = "story_id",  nullable = false) private UUID storyId;
        @Column(name = "viewer_id", nullable = false) private UUID viewerId;
    }
}
