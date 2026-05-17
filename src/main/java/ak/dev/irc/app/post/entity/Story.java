package ak.dev.irc.app.post.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import ak.dev.irc.app.post.sound.entity.PostSound;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "stories",
    indexes = {
        @Index(name = "idx_story_author",  columnList = "author_id"),
        @Index(name = "idx_story_expires", columnList = "expires_at"),
        @Index(name = "idx_story_active",  columnList = "author_id, expires_at, is_deleted"),
        @Index(name = "idx_story_type",    columnList = "story_type"),
        @Index(name = "idx_story_hl",      columnList = "highlight_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Story extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_story_author"))
    private User author;

    // ── Type & content ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "story_type", nullable = false, length = 20)
    private StoryType storyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private StoryVisibility visibility = StoryVisibility.PUBLIC;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    // ── Background (TEXT and IMAGE stories) ──────────────────────────────────

    /** "color" | "gradient" | "image" */
    @Column(name = "background_type", length = 20)
    private String backgroundType;

    @Column(name = "background_value", length = 500)
    private String backgroundValue;

    // ── Media (IMAGE and VIDEO stories) ──────────────────────────────────────

    @Column(name = "media_url", columnDefinition = "TEXT")
    private String mediaUrl;

    @Column(name = "media_s3_key", columnDefinition = "TEXT")
    private String mediaS3Key;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "thumbnail_s3_key", columnDefinition = "TEXT")
    private String thumbnailS3Key;

    /** ≤ 30s for VIDEO; 3–10s for IMAGE (display duration). */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    // ── Linked content (LINKED_* types) ──────────────────────────────────────

    @Column(name = "linked_content_id")
    private UUID linkedContentId;

    /**
     * Snapshot JSON of linked content title, thumbnail, author name —
     * renders correctly even after the original is deleted.
     */
    @Column(name = "linked_content_snapshot", columnDefinition = "TEXT")
    private String linkedContentSnapshot;

    // ── Overlays ─────────────────────────────────────────────────────────────

    /**
     * JSON array of overlay objects — text, emoji, stickers, polls, links.
     * Stored as JSON (not child table) since overlays are always loaded together.
     */
    @Column(name = "overlays_json", columnDefinition = "TEXT")
    private String overlaysJson;

    // ── Sound ─────────────────────────────────────────────────────────────────

    @OneToOne(mappedBy = "story", cascade = CascadeType.ALL,
              orphanRemoval = true, fetch = FetchType.LAZY)
    private PostSound sound;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Expires 24h after creation — set at INSERT. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── Highlight ─────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "highlight_id",
                foreignKey = @ForeignKey(name = "fk_story_highlight"))
    private StoryHighlight highlight;

    // ── Counters ──────────────────────────────────────────────────────────────

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private long viewCount = 0L;

    @Column(name = "reaction_count", nullable = false)
    @Builder.Default
    private long reactionCount = 0L;

    @Column(name = "reply_count", nullable = false)
    @Builder.Default
    private long replyCount = 0L;

    @Version
    private Long version;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
    public boolean isActive()  { return !isDeleted && !isExpired(); }

    public void incrementViews()    { this.viewCount++; }
    public void incrementReactions(){ this.reactionCount++; }
    public void incrementReplies()  { this.replyCount++; }
    public void decrementReactions(){ if (this.reactionCount > 0) this.reactionCount--; }
}
