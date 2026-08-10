package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.QuestionAnswer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionAnswerRepository extends JpaRepository<QuestionAnswer, UUID> {

    Page<QuestionAnswer> findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID questionId, Pageable pageable);

    /**
     * Every answer the {@code irc-answers} index may legitimately hold. There is
     * one document per answer and no viewer to grant an author carve-out to, so
     * held and rejected rows are excluded outright; the null case is the
     * pre-pipeline archive, which stays indexed.
     */
    @Query("""
        SELECT a FROM QuestionAnswer a
        WHERE a.deletedAt IS NULL
          AND (a.moderationStatus IS NULL
               OR a.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED)
        """)
    Page<QuestionAnswer> findIndexable(Pageable pageable);

    /** Used by the dedup window to recover the original write on a duplicate submission. */
    @Query("""
           SELECT a FROM QuestionAnswer a
           WHERE a.question.id = :questionId
             AND a.author.id   = :authorId
             AND a.body        = :body
             AND a.deletedAt   IS NULL
           ORDER BY a.createdAt DESC
           """)
    List<QuestionAnswer> findRecentByAuthorAndBody(@Param("questionId") UUID questionId,
                                                   @Param("authorId") UUID authorId,
                                                   @Param("body") String body);

    /** Top-level answers only (no reanswers). */
    @EntityGraph(attributePaths = {"author", "author.profile", "parentAnswer"})
    Page<QuestionAnswer> findByQuestionIdAndParentAnswerIsNullAndDeletedAtIsNullOrderByCreatedAtAsc(
            UUID questionId, Pageable pageable);

    /** Reanswers (replies) under a given parent answer. */
    @EntityGraph(attributePaths = {"author", "author.profile", "parentAnswer"})
    Page<QuestionAnswer> findByParentAnswerIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            UUID parentAnswerId, Pageable pageable);

    long countByParentAnswerIdAndDeletedAtIsNull(UUID parentAnswerId);

    Optional<QuestionAnswer> findByIdAndQuestionIdAndDeletedAtIsNull(UUID answerId, UUID questionId);

    @Query("SELECT a FROM QuestionAnswer a WHERE a.id = :answerId AND a.deletedAt IS NULL")
    Optional<QuestionAnswer> findByIdAndDeletedAtIsNull(@Param("answerId") UUID answerId);

    /**
     * Restriction-aware top-level answer listing — answers authored by users
     * the question author has restricted are hidden from everyone except the
     * question author and the answer author themselves.
     *
     * <p>{@code requesterId} may be null for anonymous viewers; the existence
     * sub-query then enforces full restriction.</p>
     */
    /**
     * Restriction- AND block-aware top-level answer listing.
     * - Restriction (asymmetric, set by question author) hides restricted authors.
     * - Block (symmetric, between viewer and answer author) hides each from the other.
     * The viewer always sees their own answers and the question author always
     * sees every answer, regardless of restriction. Block is enforced even on
     * the question author when the answer author has them in a block edge.
     *
     * <p>Moderation (docs/moderation/) rides on the same clause but with a
     * <b>narrower</b> carve-out than restriction: only the answer's own author
     * sees it while it is held. The question author is explicitly not included —
     * restriction is their own decision about someone else's answer, whereas a
     * held answer has not cleared the platform's checks for anyone yet.</p>
     */
    @EntityGraph(attributePaths = {"author", "author.profile", "parentAnswer"})
    @Query("""
        SELECT a FROM QuestionAnswer a
        WHERE a.question.id = :questionId
          AND a.parentAnswer IS NULL
          AND a.deletedAt IS NULL
          AND (:blockedIds IS NULL OR a.author.id NOT IN :blockedIds)
          AND (a.moderationStatus IS NULL
               OR a.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:requesterId IS NOT NULL AND a.author.id = :requesterId))
          AND (
            (:requesterId IS NOT NULL AND a.question.author.id = :requesterId)
            OR (:requesterId IS NOT NULL AND a.author.id = :requesterId)
            OR NOT EXISTS (
              SELECT 1 FROM ak.dev.irc.app.user.entity.UserRestriction r
              WHERE r.restrictor.id = a.question.author.id
                AND r.restricted.id = a.author.id
            )
          )
        ORDER BY a.createdAt ASC
        """)
    Page<QuestionAnswer> findVisibleTopLevelAnswers(@Param("questionId") UUID questionId,
                                                    @Param("requesterId") UUID requesterId,
                                                    @Param("blockedIds") List<UUID> blockedIds,
                                                    Pageable pageable);

    @EntityGraph(attributePaths = {"author", "author.profile", "parentAnswer"})
    @Query("""
        SELECT a FROM QuestionAnswer a
        WHERE a.parentAnswer.id = :parentAnswerId
          AND a.deletedAt IS NULL
          AND (:blockedIds IS NULL OR a.author.id NOT IN :blockedIds)
          AND (a.moderationStatus IS NULL
               OR a.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:requesterId IS NOT NULL AND a.author.id = :requesterId))
          AND (
            (:requesterId IS NOT NULL AND a.question.author.id = :requesterId)
            OR (:requesterId IS NOT NULL AND a.author.id = :requesterId)
            OR NOT EXISTS (
              SELECT 1 FROM ak.dev.irc.app.user.entity.UserRestriction r
              WHERE r.restrictor.id = a.question.author.id
                AND r.restricted.id = a.author.id
            )
          )
        ORDER BY a.createdAt ASC
        """)
    Page<QuestionAnswer> findVisibleReanswers(@Param("parentAnswerId") UUID parentAnswerId,
                                              @Param("requesterId") UUID requesterId,
                                              @Param("blockedIds") List<UUID> blockedIds,
                                              Pageable pageable);

    /**
     * Atomic clamp-at-zero increment/decrement of the denormalised
     * {@code replyCount}. Mirrors {@code PostCommentRepository.updateReplyCount}
     * so a high-traffic question never produces negative counters.
     */
    @Modifying
    @Query("""
        UPDATE QuestionAnswer a
        SET a.replyCount = CASE WHEN a.replyCount + :delta < 0 THEN 0
                                ELSE a.replyCount + :delta END
        WHERE a.id = :id
        """)
    void updateReplyCount(@Param("id") UUID id, @Param("delta") long delta);

    /**
     * Post-update read of the denormalised {@code replyCount}. Scalar select
     * rather than {@code entityManager.refresh}, for the same reason as
     * {@code QuestionRepository.findAnswerCount}: the publication path can run
     * from the moderation applier, where the parent is a lazy association.
     */
    @Query("SELECT a.replyCount FROM QuestionAnswer a WHERE a.id = :id")
    Long findReplyCount(@Param("id") UUID id);

    /**
     * Atomic clamp-at-zero increment/decrement of the denormalised
     * {@code reactionCount}. Mirrors {@code PostCommentRepository.updateReactionCount}
     * — using entity setter + save was racy and would silently lose updates
     * under concurrent reactions, causing the counter to drift below the
     * actual reaction-row count.
     */
    @Modifying
    @Query("""
        UPDATE QuestionAnswer a
        SET a.reactionCount = CASE WHEN a.reactionCount + :delta < 0 THEN 0
                                   ELSE a.reactionCount + :delta END
        WHERE a.id = :id
        """)
    void updateReactionCount(@Param("id") UUID id, @Param("delta") long delta);

    // ── Reconciliation source-of-truth queries ─────────────────────────────

    // Both counters are maintained at publication, not at insert, so the
    // source-of-truth rebuild has to apply the same moderation filter — otherwise
    // a reconcile would fold every held answer back into the public count.

    /** Live, published top-level answers on a question. Used to rebuild {@code question.answerCount}. */
    @Query("""
        SELECT COUNT(a) FROM QuestionAnswer a
        WHERE a.question.id = :questionId
          AND a.parentAnswer IS NULL
          AND a.deletedAt IS NULL
          AND (a.moderationStatus IS NULL
               OR a.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED)
        """)
    long countLiveTopLevelByQuestionId(@Param("questionId") UUID questionId);

    /** Live, published reanswers under a parent answer. Used to rebuild {@code answer.replyCount}. */
    @Query("""
        SELECT COUNT(a) FROM QuestionAnswer a
        WHERE a.parentAnswer.id = :parentId
          AND a.deletedAt IS NULL
          AND (a.moderationStatus IS NULL
               OR a.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED)
        """)
    long countLiveRepliesByParentId(@Param("parentId") UUID parentId);

    // ── Bulk reconcile from source-of-truth row counts ──────────────────────

    @Modifying
    @Query(value = """
        UPDATE question_answers SET reaction_count = (
            SELECT COUNT(*) FROM answer_reactions r WHERE r.answer_id = question_answers.id
        )
        """, nativeQuery = true)
    int bulkReconcileReactionCount();

    @Modifying
    @Query(value = """
        UPDATE question_answers parent SET reply_count = (
            SELECT COUNT(*) FROM question_answers a
            WHERE a.parent_answer_id = parent.id AND a.deleted_at IS NULL
              AND (a.moderation_status IS NULL OR a.moderation_status = 'APPROVED')
        )
        """, nativeQuery = true)
    int bulkReconcileReplyCount();

    /**
     * Pre-cascade lookup — every answer ID under a question, used to drive the
     * S3 cleanup pass before the rows themselves are deleted.
     */
    @Query("SELECT a.id FROM QuestionAnswer a WHERE a.question.id = :questionId")
    List<UUID> findIdsByQuestionId(@Param("questionId") UUID questionId);

    /** S3 keys we need to delete from object storage before the row is dropped. */
    @Query("""
        SELECT a.mediaS3Key, a.mediaThumbnailS3Key, a.voiceS3Key
        FROM QuestionAnswer a
        WHERE a.question.id = :questionId
        """)
    List<Object[]> findMediaKeysByQuestionId(@Param("questionId") UUID questionId);

    /** Cascade purge — used when the parent question is hard-deleted. */
    @Modifying
    @Query("DELETE FROM QuestionAnswer a WHERE a.question.id = :questionId")
    int deleteAllByQuestionId(@Param("questionId") UUID questionId);

    /**
     * Bulk fetch of {@code (answerId, reactionType)} for a viewer over a page
     * of answers — supports rendering "myReaction" without N round-trips.
     */
    @Query("""
        SELECT r.id.answerId, r.reactionType
        FROM ak.dev.irc.app.qna.entity.AnswerReaction r
        WHERE r.user.id = :userId
          AND r.id.answerId IN :answerIds
        """)
    List<Object[]> findMyReactionsForAnswers(@Param("userId") UUID userId,
                                              @Param("answerIds") List<UUID> answerIds);

    // ── Full-text search on answer body (block-aware) ──────────────
    @Query(value = """
        SELECT a.id, ts_rank_cd(to_tsvector('simple', coalesce(a.body, '')),
                                websearch_to_tsquery('simple', :q)) AS score
        FROM question_answers a
        WHERE a.deleted_at IS NULL
          AND to_tsvector('simple', coalesce(a.body, ''))
              @@ websearch_to_tsquery('simple', :q)
          AND (a.moderation_status IS NULL OR a.moderation_status = 'APPROVED')
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR a.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, a.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchFts(@Param("q") String q,
                              @Param("blockedIds") java.util.UUID[] blockedIds,
                              @Param("limit") int limit);
}
