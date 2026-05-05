package ak.dev.irc.app.qna.entity;

import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "question_answers",
        indexes = {
                @Index(name = "idx_qanswer_question", columnList = "question_id"),
                @Index(name = "idx_qanswer_author", columnList = "author_id"),
                @Index(name = "idx_qanswer_deleted", columnList = "deleted_at"),
                @Index(name = "idx_qanswer_parent", columnList = "parent_answer_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionAnswer extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qanswer_question"))
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qanswer_author"))
    private User author;

    /** Null = top-level answer; non-null = reanswer (reply) under another answer. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_answer_id",
            foreignKey = @ForeignKey(name = "fk_qanswer_parent"))
    private QuestionAnswer parentAnswer;

    @OneToMany(mappedBy = "parentAnswer", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<QuestionAnswer> replies = new ArrayList<>();

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    // ── Media attachments ───────────────────────────────────────
    @Column(name = "media_url", columnDefinition = "TEXT")
    private String mediaUrl;

    /** S3/R2 object key for the media file */
    @Column(name = "media_s3_key", columnDefinition = "TEXT")
    private String mediaS3Key;

    @Column(name = "media_type", length = 20)
    private String mediaType;       // IMAGE, VIDEO

    @Column(name = "media_thumbnail_url", columnDefinition = "TEXT")
    private String mediaThumbnailUrl;

    /** S3/R2 object key for the media thumbnail */
    @Column(name = "media_thumbnail_s3_key", columnDefinition = "TEXT")
    private String mediaThumbnailS3Key;

    // ── Voice recording ──────────────────────────────────────────
    @Column(name = "voice_url", columnDefinition = "TEXT")
    private String voiceUrl;

    /** S3/R2 object key for the voice recording */
    @Column(name = "voice_s3_key", columnDefinition = "TEXT")
    private String voiceS3Key;

    @Column(name = "voice_duration_seconds")
    private Integer voiceDurationSeconds;

    // ── Links ────────────────────────────────────────────────────
    @Column(name = "links", columnDefinition = "TEXT")
    private String links;           // comma-separated URLs

    // ── Status ───────────────────────────────────────────────────
    @Column(name = "reaction_count", nullable = false)
    @Builder.Default
    private Long reactionCount = 0L;

    /**
     * Denormalised count of non-deleted reanswers under this answer — mirrors
     * {@code PostComment.replyCount} so listings can render thread sizes
     * without a per-row count query.
     */
    @Column(name = "reply_count", nullable = false)
    @Builder.Default
    private Long replyCount = 0L;

    @Column(name = "is_accepted", nullable = false)
    @Builder.Default
    private boolean accepted = false;

    @Column(name = "is_edited", nullable = false)
    @Builder.Default
    private boolean edited = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── Attachments (PDF, Word, ZIP, video, audio, images) ────────
    @OneToMany(mappedBy = "answer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<AnswerAttachment> attachments = new ArrayList<>();

    // ── Sources / references ────────────────────────────────────
    @OneToMany(mappedBy = "answer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<AnswerSource> sources = new ArrayList<>();

    // ── Feedback from question author ────────────────────────────
    @OneToMany(mappedBy = "answer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<AnswerFeedback> feedbacks = new ArrayList<>();

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void incrementReactions() {
        this.reactionCount = (this.reactionCount == null ? 0L : this.reactionCount) + 1L;
    }

    public void decrementReactions() {
        if (this.reactionCount != null && this.reactionCount > 0) {
            this.reactionCount--;
        }
    }

    public void incrementReplies() {
        this.replyCount = (this.replyCount == null ? 0L : this.replyCount) + 1L;
    }

    public void decrementReplies() {
        if (this.replyCount != null && this.replyCount > 0) {
            this.replyCount--;
        }
    }
}