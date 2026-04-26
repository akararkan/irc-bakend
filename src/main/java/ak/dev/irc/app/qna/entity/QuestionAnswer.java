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
                @Index(name = "idx_qanswer_deleted", columnList = "deleted_at")
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
}