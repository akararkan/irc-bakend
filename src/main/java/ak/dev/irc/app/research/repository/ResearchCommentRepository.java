package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchCommentRepository extends JpaRepository<ResearchComment, UUID> {

    /** Top-level comments for a research (parent IS NULL) — hidden comments are excluded */
    Page<ResearchComment> findByResearchIdAndParentIsNullAndDeletedAtIsNullAndIsHiddenFalseOrderByCreatedAtDesc(
            UUID researchId, Pageable pageable);

    /** Top-level comments for a research (parent IS NULL) — includes hidden comments */
    Page<ResearchComment> findByResearchIdAndParentIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID researchId, Pageable pageable);

    long countByResearchIdAndDeletedAtIsNull(UUID researchId);

    // ── Comment likes (junction table managed via native queries) ─────────────

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM research_comment_likes
            WHERE comment_id = :commentId AND user_id = :userId
        )
    """, nativeQuery = true)
    boolean existsLikeByCommentIdAndUserId(@Param("commentId") UUID commentId,
                                           @Param("userId") UUID userId);

    @Modifying
    @Query(value = """
        INSERT INTO research_comment_likes (comment_id, user_id, created_at)
        VALUES (:commentId, :userId, NOW())
        ON CONFLICT DO NOTHING
    """, nativeQuery = true)
    void insertCommentLike(@Param("commentId") UUID commentId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = """
        DELETE FROM research_comment_likes
        WHERE comment_id = :commentId AND user_id = :userId
    """, nativeQuery = true)
    void deleteCommentLike(@Param("commentId") UUID commentId, @Param("userId") UUID userId);

    /**
     * Drop every like on a comment in one statement — used when a comment is
     * soft-deleted so the join table doesn't leak rows pointing at a hidden
     * comment. Mirrors {@code PostCommentReactionRepository.deleteAllByCommentId}.
     */
    @Modifying
    @Query(value = """
        DELETE FROM research_comment_likes
        WHERE comment_id = :commentId
    """, nativeQuery = true)
    void deleteAllLikesByCommentId(@Param("commentId") UUID commentId);

    /**
     * Atomic clamp-at-zero increment/decrement of the denormalised
     * {@code likeCount}. Mirrors {@code PostCommentRepository.updateReactionCount}
     * — using entity setter + save was racy and would silently lose updates
     * under concurrent reactions, causing the counter to drift below the
     * actual reaction-row count.
     */
    @Modifying
    @Query("UPDATE ResearchComment c SET c.likeCount = CASE WHEN c.likeCount + :delta < 0 THEN 0 ELSE c.likeCount + :delta END WHERE c.id = :id")
    void updateLikeCount(@Param("id") UUID id, @Param("delta") long delta);

    /**
     * Atomic clamp-at-zero increment/decrement of the denormalised
     * {@code replyCount}. Same rationale as {@link #updateLikeCount}.
     */
    @Modifying
    @Query("UPDATE ResearchComment c SET c.replyCount = CASE WHEN c.replyCount + :delta < 0 THEN 0 ELSE c.replyCount + :delta END WHERE c.id = :id")
    void updateReplyCount(@Param("id") UUID id, @Param("delta") long delta);

    // ── Reconciliation queries (used by CounterReconciler to rebuild counters
    //    from the source-of-truth row counts, not from the possibly-drifted
    //    denormalised columns) ─────────────────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM research_comment_likes WHERE comment_id = :commentId
    """, nativeQuery = true)
    long countLikesByCommentId(@Param("commentId") UUID commentId);

    @Query("SELECT COUNT(c) FROM ResearchComment c WHERE c.parent.id = :parentId AND c.deletedAt IS NULL")
    long countLiveRepliesByParentId(@Param("parentId") UUID parentId);

    /** Live (non-deleted) comments on a research. Used to rebuild {@code research.commentCount}. */
    @Query("SELECT COUNT(c) FROM ResearchComment c WHERE c.research.id = :researchId AND c.deletedAt IS NULL")
    long countLiveByResearchId(@Param("researchId") UUID researchId);

    /** Used by the dedup window to recover the original write on a duplicate submission. */
    @Query("""
           SELECT c FROM ResearchComment c
           WHERE c.research.id = :researchId
             AND c.user.id     = :userId
             AND c.content     = :content
             AND c.deletedAt   IS NULL
           ORDER BY c.createdAt DESC
           """)
    List<ResearchComment> findRecentByAuthorAndContent(@Param("researchId") UUID researchId,
                                                       @Param("userId") UUID userId,
                                                       @Param("content") String content);

    // ── Bulk reconcile from source-of-truth row counts ──────────────────────

    @Modifying
    @Query(value = """
        UPDATE research_comments SET like_count = (
            SELECT COUNT(*) FROM research_comment_likes l WHERE l.comment_id = research_comments.id
        )
        """, nativeQuery = true)
    int bulkReconcileLikeCount();

    @Modifying
    @Query(value = """
        UPDATE research_comments parent SET reply_count = (
            SELECT COUNT(*) FROM research_comments c
            WHERE c.parent_id = parent.id AND c.deleted_at IS NULL
        )
        """, nativeQuery = true)
    int bulkReconcileReplyCount();
}
