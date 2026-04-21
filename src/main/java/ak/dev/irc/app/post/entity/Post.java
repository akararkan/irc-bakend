package ak.dev.irc.app.post.entity;


import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.enums.PostVisibility;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "posts", indexes = {
        @Index(name = "idx_post_author",  columnList = "author_id"),
        @Index(name = "idx_post_type",    columnList = "post_type"),
        @Index(name = "idx_post_status",  columnList = "status"),
        @Index(name = "idx_post_created", columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    // ── content ──────────────────────────────────────────────
    @Column(columnDefinition = "TEXT")
    private String textContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false)
    private PostType postType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PostStatus status = PostStatus.PUBLISHED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PostVisibility visibility = PostVisibility.PUBLIC;

    // (voice/audio posts removed — voice fields intentionally omitted)

    // ── story TTL ─────────────────────────────────────────────
    /** Populated only for PostType.STORY – null means no expiry */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // ── reel / embedded audio ─────────────────────────────────
    @Column(name = "audio_track_url")
    private String audioTrackUrl;

    @Column(name = "audio_track_name")
    private String audioTrackName;

    // ── location ──────────────────────────────────────────────
    @Column(name = "location_name")
    private String locationName;

    @Column(name = "location_lat")
    private Double locationLat;

    @Column(name = "location_lng")
    private Double locationLng;

    // ── sharing ───────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shared_post_id")
    private Post sharedPost;

    @Column(name = "share_link", unique = true)
    private String shareLink;

    // ── denormalised counters (for fast feed queries) ─────────
    @Builder.Default @Column(name = "reaction_count") private Long reactionCount = 0L;
    @Builder.Default @Column(name = "comment_count")  private Long commentCount  = 0L;
    @Builder.Default @Column(name = "share_count")    private Long shareCount    = 0L;
    @Builder.Default @Column(name = "view_count")     private Long viewCount     = 0L;

    // ── relations ─────────────────────────────────────────────
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostMedia> mediaList = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostReaction> reactions = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostComment> comments = new ArrayList<>();

    // ── audit ─────────────────────────────────────────────────
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── helpers ───────────────────────────────────────────────
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public void incrementReactions() { this.reactionCount++; }
    public void decrementReactions() { if (this.reactionCount > 0) this.reactionCount--; }
    public void incrementComments()  { this.commentCount++; }
    public void decrementComments()  { if (this.commentCount > 0) this.commentCount--; }
    public void incrementShares()    { this.shareCount++; }
    public void incrementViews()     { this.viewCount++; }
}