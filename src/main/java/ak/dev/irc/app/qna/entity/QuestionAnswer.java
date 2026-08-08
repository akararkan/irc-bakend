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
                @Index(name = "idx_qanswer_parent", columnList = "parent_answer_id"),
                @Index(name = "idx_qanswer_moderation", columnList = "moderation_status")
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

    /**
     * The answer this reply was actually aimed at — captured BEFORE depth-1
     * hoisting, so when a reply-to-a-reply is hoisted to a sibling of the root,
     * we still know who/what it was replying to. Lets the UI render "replying to
     * @X" natively. Equals {@code parentAnswer} when replying to a top-level
     * answer; null for top-level answers.
     */
    @Column(name = "reply_to_answer_id")
    private UUID replyToAnswerId;

    /** Author of {@link #replyToAnswerId} — the "@X" the reply addresses. */
    @Column(name = "reply_to_user_id")
    private UUID replyToUserId;

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

    // ── Automated moderation (docs/moderation/) ──────────────────
    /**
     * Verdict on this answer's text. Null means "never scored" — every answer
     * that predates the pipeline — and reads treat null as visible so switching
     * the classifier on does not retroactively hide the archive. Reanswers carry
     * the same column; a reply is just an answer with a parent.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", length = 20)
    private ak.dev.irc.app.moderation.enums.ModerationStatus moderationStatus;

    @Column(name = "moderation_decided_at")
    private LocalDateTime moderationDecidedAt;

    /**
     * Stamped the first time this answer was actually published. Answer counts
     * are deferred to publication, so the applier has to distinguish a first
     * approval (bump {@code question.answerCount} / the parent's
     * {@code replyCount}) from a re-approval after an edit put the answer back
     * under review (counters already reflect it).
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

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

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** True while the answer is written but not published — visible to its author only. */
    public boolean isModerationHeld() {
        return moderationStatus != null && moderationStatus.held();
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