package ak.dev.irc.app.research.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(
        name = "research_comments",
        indexes = {
                @Index(name = "idx_rcomment_research", columnList = "research_id"),
                @Index(name = "idx_rcomment_user",     columnList = "user_id"),
                @Index(name = "idx_rcomment_parent",   columnList = "parent_id"),
                @Index(name = "idx_rcomment_deleted",  columnList = "deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchComment extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "research_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_rcomment_research"))
    private Research research;

    /** The user who posted this comment (any role can comment) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_rcomment_user"))
    private User user;

    // ── Nested replies ────────────────────────────────────────────────────────

    /** Parent comment (null = top-level comment) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id",
            foreignKey = @ForeignKey(name = "fk_rcomment_parent"))
    private ResearchComment parent;

    @org.hibernate.annotations.BatchSize(size = 50)   // comment page: replies load in IN batches, not per row
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ResearchComment> replies = new ArrayList<>();

    // ── Content ───────────────────────────────────────────────────────────────

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    // ── media attachment (image or video) ────────────────────────────────────
    @Column(name = "media_url")
    private String mediaUrl;

    /** S3/R2 object key for the comment media */
    @Column(name = "media_s3_key", columnDefinition = "TEXT")
    private String mediaS3Key;

    @Column(name = "media_type")
    private String mediaType;          // IMAGE or VIDEO

    @Column(name = "media_thumbnail_url")
    private String mediaThumbnailUrl;

    /** S3/R2 object key for the comment media thumbnail */
    @Column(name = "media_thumbnail_s3_key", columnDefinition = "TEXT")
    private String mediaThumbnailS3Key;

    // ── voice comment ──────────────────────────────────────────────────────────
    @Column(name = "voice_url")
    private String voiceUrl;

    /** S3/R2 object key for the voice recording. */
    @Column(name = "voice_s3_key", columnDefinition = "TEXT")
    private String voiceS3Key;

    @Column(name = "voice_duration_seconds")
    private Integer voiceDurationSeconds;

    /** Number of likes on this comment (denormalised) */
    @Column(name = "like_count", nullable = false)
    @Builder.Default
    private Long likeCount = 0L;

    @Column(name = "reply_count", nullable = false)
    @Builder.Default
    private Long replyCount = 0L;

    /** Whether the comment has been edited */
    @Column(name = "is_edited", nullable = false)
    @Builder.Default
    private boolean isEdited = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    /** Soft-delete */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Hidden by post owner or moderator — comment remains in DB but not shown to regular users */
    @Column(name = "is_hidden", nullable = false)
    @Builder.Default
    private boolean isHidden = false;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hidden_by_user_id", foreignKey = @ForeignKey(name = "fk_rcomment_hidden_by"))
    private ak.dev.irc.app.user.entity.User hiddenBy;

    // ── Automated moderation (docs/moderation/) ──────────────────────────────

    /**
     * Verdict of the automated text pass. Deliberately separate from
     * {@link #isHidden}: hiding is a human act by the research owner and is
     * reversible from the UI, while this column is machine state the moderation
     * applier owns. Null means the row predates moderation or carried no text
     * to score.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", length = 20)
    private ak.dev.irc.app.moderation.enums.ModerationStatus moderationStatus;

    @Column(name = "moderation_decided_at")
    private LocalDateTime moderationDecidedAt;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isDeleted()    { return deletedAt != null; }
    public boolean isTopLevel()   { return parent == null; }

    /** Held or refused by the classifier — readable by its author and the research owner only. */
    public boolean isHeldForModeration() {
        return moderationStatus != null
                && moderationStatus != ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED;
    }
}