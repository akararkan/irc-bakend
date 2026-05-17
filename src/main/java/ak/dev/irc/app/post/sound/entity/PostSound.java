package ak.dev.irc.app.post.sound.entity;

import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.Story;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "post_sounds",
    indexes = {
        @Index(name = "idx_psound_post",  columnList = "post_id"),
        @Index(name = "idx_psound_sound", columnList = "sound_id"),
        @Index(name = "idx_psound_story", columnList = "story_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostSound {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", unique = true,
                foreignKey = @ForeignKey(name = "fk_psound_post"))
    private Post post;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", unique = true,
                foreignKey = @ForeignKey(name = "fk_psound_story"))
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sound_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_psound_sound"))
    private Sound sound;

    /** Start offset in seconds within the full audio track. */
    @Column(name = "clip_start_seconds", nullable = false)
    @Builder.Default
    private int clipStartSeconds = 0;

    /** Volume level 0.0–1.0 for mixing with video/image audio. */
    @Column(name = "volume", nullable = false)
    @Builder.Default
    private float volume = 0.8f;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
