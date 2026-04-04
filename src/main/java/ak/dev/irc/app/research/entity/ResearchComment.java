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

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ResearchComment> replies = new ArrayList<>();

    // ── Content ───────────────────────────────────────────────────────────────

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isDeleted()    { return deletedAt != null; }
    public boolean isTopLevel()   { return parent == null; }
}
