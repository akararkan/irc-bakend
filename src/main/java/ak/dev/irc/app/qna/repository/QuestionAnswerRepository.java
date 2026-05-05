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

    /** Top-level answers only (no reanswers). */
    @EntityGraph(attributePaths = {"author", "parentAnswer"})
    Page<QuestionAnswer> findByQuestionIdAndParentAnswerIsNullAndDeletedAtIsNullOrderByCreatedAtAsc(
            UUID questionId, Pageable pageable);

    /** Reanswers (replies) under a given parent answer. */
    @EntityGraph(attributePaths = {"author", "parentAnswer"})
    Page<QuestionAnswer> findByParentAnswerIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            UUID parentAnswerId, Pageable pageable);

    long countByParentAnswerIdAndDeletedAtIsNull(UUID parentAnswerId);

    Optional<QuestionAnswer> findByIdAndQuestionIdAndDeletedAtIsNull(UUID answerId, UUID questionId);

    Optional<QuestionAnswer> findByIdAndDeletedAtIsNull(UUID answerId);

    /**
     * Restriction-aware top-level answer listing — answers authored by users
     * the question author has restricted are hidden from everyone except the
     * question author and the answer author themselves.
     *
     * <p>{@code requesterId} may be null for anonymous viewers; the existence
     * sub-query then enforces full restriction.</p>
     */
    @EntityGraph(attributePaths = {"author", "parentAnswer"})
    @Query("""
        SELECT a FROM QuestionAnswer a
        WHERE a.question.id = :questionId
          AND a.parentAnswer IS NULL
          AND a.deletedAt IS NULL
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
                                                    Pageable pageable);

    @EntityGraph(attributePaths = {"author", "parentAnswer"})
    @Query("""
        SELECT a FROM QuestionAnswer a
        WHERE a.parentAnswer.id = :parentAnswerId
          AND a.deletedAt IS NULL
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

    // ── Full-text search on answer body ─────────────────────────────
    @Query(value = """
        SELECT a.id, ts_rank_cd(to_tsvector('simple', coalesce(a.body, '')),
                                websearch_to_tsquery('simple', :q)) AS score
        FROM question_answers a
        WHERE a.deleted_at IS NULL
          AND to_tsvector('simple', coalesce(a.body, ''))
              @@ websearch_to_tsquery('simple', :q)
        ORDER BY score DESC, a.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchFts(@Param("q") String q, @Param("limit") int limit);
}
