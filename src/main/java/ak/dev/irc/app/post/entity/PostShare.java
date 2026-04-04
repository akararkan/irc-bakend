package ak.dev.irc.app.post.entity;

import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "post_shares", indexes = {
        @Index(name = "idx_share_post", columnList = "post_id"),
        @Index(name = "idx_share_user", columnList = "sharer_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostShare {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sharer_id", nullable = false)
    private User sharer;

    /** Optional caption added on share */
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    /** Platform where the post was shared (INTERNAL, EXTERNAL_LINK, etc.) */
    @Column(name = "share_platform")
    private String sharePlatform;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}