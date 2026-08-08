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

    /**
     * Top-level comments for a research (parent IS NULL) — hidden comments are
     * excluded. Comment author + profile are fetch-joined: the mapper reads
     * {@code c.getUser().getProfileImage()} per row, and {@code User.profile}
     * is a non-proxyable mappedBy 1:1. Explicit countQuery (fetch join).
     *
     * <p>The moderation predicate rides alongside the {@code isHidden} one: a
     * comment the classifier held is visible to whoever wrote it and to nobody
     * else on this path (the research owner uses the sibling query). A null
     * status is a row moderation never scored and stays visible. {@code viewerId}
     * is null for anonymous readers, and {@code c.user.id = null} is never true
     * in SQL, so the author clause simply drops out.</p>
     */
    @Query(value = """
        SELECT c FROM ResearchComment c
        JOIN FETCH c.user u
        LEFT JOIN FETCH u.profile
        WHERE c.research.id = :researchId
          AND c.parent IS NULL
          AND c.deletedAt IS NULL
          AND c.isHidden = false
          AND (c.moderationStatus IS NULL
               OR c.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR c.user.id = :viewerId)
        ORDER BY c.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(c) FROM ResearchComment c
        WHERE c.research.id = :researchId
          AND c.parent IS NULL AND c.deletedAt IS NULL AND c.isHidden = false
          AND (c.moderationStatus IS NULL
               OR c.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR c.user.id = :viewerId)
        """)
    Page<ResearchComment> findVisibleTopLevel(@Param("researchId") UUID researchId,
                                              @Param("viewerId") UUID viewerId,
                                              Pageable pageable);

    /**
     * Top-level comments for a research (parent IS NULL) — the research owner's
     * view, so it includes both hidden and moderation-held rows. The owner is
     * the one person who needs to see everything attached to their paper.
     */
    @Query(value = """
        SELECT c FROM ResearchComment c
        JOIN FETCH c.user u
        LEFT JOIN FETCH u.profile
        WHERE c.research.id = :researchId
          AND c.parent IS NULL
          AND c.deletedAt IS NULL
        ORDER BY c.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(c) FROM ResearchComment c
        WHERE c.research.id = :researchId
          AND c.parent IS NULL AND c.deletedAt IS NULL
        """)
    Page<ResearchComment> findByResearchIdAndParentIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(
            @Param("researchId") UUID researchId, Pageable pageable);

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
     * Cascade purge of the like rows for an entire research thread. The FK
     * {@code research_comment_likes.comment_id → research_comments(id)} is
     * NO ACTION (Hibernate's default), so the likes must go first or the
     * comment purge fails with a constraint violation.
     */
    @Modifying
    @Query(value = """
        DELETE FROM research_comment_likes
        WHERE comment_id IN (SELECT id FROM research_comments WHERE research_id = :researchId)
    """, nativeQuery = true)
    int deleteAllLikesByResearchId(@Param("researchId") UUID researchId);

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

    /**
     * Cascade purge — used when the parent research is hard-deleted. Comments
     * include nested replies under the same {@code research_id}, so a single
     * statement clears the whole thread tree. The companion
     * {@code research_comment_likes} rows are removed by ON DELETE CASCADE.
     */
    @Modifying
    @Query("DELETE FROM ResearchComment c WHERE c.research.id = :researchId")
    int deleteAllByResearchId(@Param("researchId") UUID researchId);

    @Query("SELECT c.id FROM ResearchComment c WHERE c.research.id = :researchId")
    List<UUID> findIdsByResearchId(@Param("researchId") UUID researchId);
}
