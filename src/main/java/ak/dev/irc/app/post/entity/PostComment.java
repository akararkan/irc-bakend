package ak.dev.irc.app.post.entity;


import jakarta.persistence.*;
import lombok.*;
import ak.dev.irc.app.user.entity.User;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "post_comments", indexes = {
        @Index(name = "idx_comment_post",   columnList = "post_id"),
        @Index(name = "idx_comment_parent", columnList = "parent_id"),
        @Index(name = "idx_comment_author", columnList = "author_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PostComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    // ── threading ─────────────────────────────────────────────
    /** Null = top-level comment; non-null = reply */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private PostComment parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostComment> replies = new ArrayList<>();

    // ── content ───────────────────────────────────────────────
    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    // ── voice comment (unique feature) ───────────────────────
    @Column(name = "voice_url")
    private String voiceUrl;

    @Column(name = "voice_duration_seconds")
    private Integer voiceDurationSeconds;

    @Column(name = "voice_transcript", columnDefinition = "TEXT")
    private String voiceTranscript;

    @Column(name = "waveform_data", columnDefinition = "TEXT")
    private String waveformData;

    // ── reactions & counters ──────────────────────────────────
    @OneToMany(mappedBy = "comment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PostCommentReaction> reactions = new ArrayList<>();

    @Builder.Default
    @Column(name = "reaction_count")
    private Long reactionCount = 0L;

    @Builder.Default
    @Column(name = "reply_count")
    private Long replyCount = 0L;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;

    // ── audit ─────────────────────────────────────────────────
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void incrementReactions() { this.reactionCount++; }
    public void decrementReactions() { if (this.reactionCount > 0) this.reactionCount--; }
    public void incrementReplies()   { this.replyCount++; }
    public void decrementReplies()   { if (this.replyCount > 0) this.replyCount--; }
}