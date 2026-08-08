package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <h3>Moderation visibility</h3>
 * Every public read below carries the same predicate: a question is visible when
 * it was never scored ({@code moderationStatus IS NULL} — the pre-pipeline
 * archive and anything written while the classifier was off), when it cleared,
 * or when the viewer is its own author. Held and rejected questions are
 * therefore hidden from feeds, search and detail without a second filtering pass
 * in the service layer. {@link #adminBrowse} deliberately omits it: the review
 * queue exists precisely to look at what nobody else can see.
 */
@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /** Admin browse (docs/admin/research-qna.md §4) — every filter optional. */
    @Query(value = """
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE (:status IS NULL OR q.status = :status)
          AND (:authorId IS NULL OR a.id = :authorId)
          AND (:text IS NULL OR LOWER(q.title) LIKE LOWER(CONCAT('%', CAST(:text AS string), '%')))
          AND (:lockedOnly IS NULL OR q.answersLocked = :lockedOnly)
          AND (:unanswered IS NULL OR (CASE WHEN q.answerCount = 0 THEN TRUE ELSE FALSE END) = :unanswered)
          AND q.deletedAt IS NULL
        ORDER BY q.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(q) FROM Question q
        WHERE (:status IS NULL OR q.status = :status)
          AND (:authorId IS NULL OR q.author.id = :authorId)
          AND (:text IS NULL OR LOWER(q.title) LIKE LOWER(CONCAT('%', CAST(:text AS string), '%')))
          AND (:lockedOnly IS NULL OR q.answersLocked = :lockedOnly)
          AND (:unanswered IS NULL OR (CASE WHEN q.answerCount = 0 THEN TRUE ELSE FALSE END) = :unanswered)
          AND q.deletedAt IS NULL
        """)
    Page<Question> adminBrowse(@Param("status") ak.dev.irc.app.qna.enums.QuestionStatus status,
                               @Param("authorId") UUID authorId,
                               @Param("text") String text,
                               @Param("lockedOnly") Boolean lockedOnly,
                               @Param("unanswered") Boolean unanswered,
                               Pageable pageable);

    @Query("SELECT COUNT(q) FROM Question q WHERE q.deletedAt IS NULL")
    long countByDeletedAtIsNull();

    /**
     * Offset feed. Split out from the old derived finder so the moderation
     * predicate has a {@code viewerId} to grant the author-sees-own carve-out;
     * an anonymous viewer passes null and sees published questions only.
     */
    @Query(value = """
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE q.deletedAt IS NULL
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(q) FROM Question q
        WHERE q.deletedAt IS NULL
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND q.author.id = :viewerId))
        """)
    Page<Question> findFeed(@Param("viewerId") UUID viewerId, Pageable pageable);

    /**
     * Every question the {@code irc-qna} index may legitimately hold. The
     * author-sees-own carve-out has no meaning here — Elasticsearch has one copy
     * per document and no viewer — so held and rejected rows are simply excluded.
     */
    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED)
        ORDER BY q.createdAt DESC
        """)
    Page<Question> findIndexable(Pageable pageable);

    @Query(value = """
            SELECT CAST(date_trunc('day', q.created_at) AS date), COUNT(*)
            FROM questions q
            WHERE q.created_at >= :from AND q.deleted_at IS NULL
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> createdPerDay(@Param("from") java.time.LocalDateTime from);

    // Cursor-paginated feed. Author + profile are fetch-joined on every feed
    // variant: the card mapper reads author.getProfileImage(), and User.profile
    // is a non-proxyable mappedBy 1:1 — without the fetch each card costs an
    // author SELECT + a profile SELECT.
    // O(log n) deep paging that does not degrade as
    // the user scrolls. Split into two methods so :cursor has a concrete type
    // bound on Postgres.
    @Query("""
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE q.deletedAt IS NULL
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedFirstPage(@Param("viewerId") UUID viewerId, Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE q.deletedAt IS NULL
          AND q.createdAt < :cursor
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedAfter(@Param("viewerId") UUID viewerId,
                                 @Param("cursor") java.time.LocalDateTime cursor,
                                 Pageable pageable);

    // ── Block-aware feed variants ────────────────────────────────────
    // Drop questions whose author is in any block-relationship with the
    // viewer in the same query — no per-row scan, single index hit.

    @Query(value = """
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE q.deletedAt IS NULL
          AND a.id NOT IN :blockedIds
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(q) FROM Question q
        WHERE q.deletedAt IS NULL
          AND q.author.id NOT IN :blockedIds
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND q.author.id = :viewerId))
        """)
    Page<Question> findFeedExcluding(@Param("viewerId") UUID viewerId,
                                     @Param("blockedIds") List<UUID> blockedIds,
                                     Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE q.deletedAt IS NULL
          AND a.id NOT IN :blockedIds
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedFirstPageExcluding(@Param("viewerId") UUID viewerId,
                                              @Param("blockedIds") List<UUID> blockedIds,
                                              Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE q.deletedAt IS NULL
          AND q.createdAt < :cursor
          AND a.id NOT IN :blockedIds
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedAfterExcluding(@Param("viewerId") UUID viewerId,
                                          @Param("cursor") java.time.LocalDateTime cursor,
                                          @Param("blockedIds") List<UUID> blockedIds,
                                          Pageable pageable);

    // Following feed: questions from followed users
    @Query(value = """
        SELECT q FROM Question q
        JOIN FETCH q.author a
        LEFT JOIN FETCH a.profile
        WHERE a.id IN :authorIds
          AND q.deletedAt IS NULL
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND a.id = :viewerId))
        ORDER BY q.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(q) FROM Question q
        WHERE q.author.id IN :authorIds
          AND q.deletedAt IS NULL
          AND (q.moderationStatus IS NULL
               OR q.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR (:viewerId IS NOT NULL AND q.author.id = :viewerId))
        """)
    Page<Question> findFollowingFeed(@Param("viewerId") UUID viewerId,
                                     @Param("authorIds") List<UUID> authorIds,
                                     Pageable pageable);

    Page<Question> findByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

    /** Profile-stat count: a user's non-deleted questions. */
    long countByAuthorIdAndDeletedAtIsNull(UUID authorId);

    Optional<Question> findByIdAndDeletedAtIsNull(UUID id);

    /** Atomic view-count bump — runs in its own write tx so the read path can stay readOnly. */
    @Modifying
    @Query("UPDATE Question q SET q.viewCount = q.viewCount + 1 WHERE q.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    /** Atomic share-count bump — invoked by the share endpoint. */
    @Modifying
    @Query("UPDATE Question q SET q.shareCount = q.shareCount + 1 WHERE q.id = :id")
    void incrementShareCount(@Param("id") UUID id);

    /**
     * Atomic clamp-at-zero increment/decrement of the denormalised
     * {@code answerCount}. Replaces the racy
     * {@code question.setAnswerCount(getAnswerCount() ± 1); save(question)}
     * which lost updates under concurrent answer creates/deletes.
     */
    @Modifying
    @Query("UPDATE Question q SET q.answerCount = CASE WHEN q.answerCount + :delta < 0 THEN 0 ELSE q.answerCount + :delta END WHERE q.id = :id")
    void adjustAnswerCount(@Param("id") UUID id, @Param("delta") long delta);

    /**
     * Post-update read of the denormalised count. A scalar select rather than
     * {@code entityManager.refresh}: the publication path can be driven by the
     * moderation applier, where the question arrives as a lazy association and
     * refreshing it is both awkward and unnecessary.
     */
    @Query("SELECT q.answerCount FROM Question q WHERE q.id = :id")
    Long findAnswerCount(@Param("id") UUID id);

    /**
     * Atomic {@code OPEN → ANSWERED}. Conditional in SQL so a question the author
     * closed or archived while an answer sat in moderation is not reopened when
     * that answer is finally released.
     */
    @Modifying
    @Query("UPDATE Question q SET q.status = ak.dev.irc.app.qna.enums.QuestionStatus.ANSWERED "
            + "WHERE q.id = :id AND q.status = ak.dev.irc.app.qna.enums.QuestionStatus.OPEN")
    void markAnsweredIfOpen(@Param("id") UUID id);

    /** Atomic clamp-at-zero adjust of the denormalised {@code acceptedAnswerCount} (D2). */
    @Modifying
    @Query("UPDATE Question q SET q.acceptedAnswerCount = CASE WHEN q.acceptedAnswerCount + :delta < 0 THEN 0 ELSE q.acceptedAnswerCount + :delta END WHERE q.id = :id")
    void adjustAcceptedAnswerCount(@Param("id") UUID id, @Param("delta") long delta);

    /** Batch-load (questionId, tagName) rows for a page of questions (D1) — avoids the per-row lazy N+1. */
    @Query(value = "SELECT question_id, tag_name FROM question_tags WHERE question_id IN (:ids)", nativeQuery = true)
    List<Object[]> findTagsByQuestionIds(@Param("ids") List<UUID> ids);

    /**
     * Atomic clamp-at-zero increment/decrement of {@code saveCount}. Mirrors
     * {@code PostRepository.adjustSaveCount}.
     */
    @Modifying
    @Query("UPDATE Question q SET q.saveCount = CASE WHEN q.saveCount + :delta < 0 THEN 0 ELSE q.saveCount + :delta END WHERE q.id = :id")
    void adjustSaveCount(@Param("id") UUID id, @Param("delta") long delta);

    // ── Bulk reconcile from source-of-truth row counts ──────────────────────

    @Modifying
    @Query(value = """
        UPDATE questions SET answer_count = (
            SELECT COUNT(*) FROM question_answers a
            WHERE a.question_id = questions.id
              AND a.parent_answer_id IS NULL
              AND a.deleted_at IS NULL
              AND (a.moderation_status IS NULL OR a.moderation_status = 'APPROVED')
        )
        """, nativeQuery = true)
    int bulkReconcileAnswerCount();

    @Modifying
    @Query(value = """
        UPDATE questions SET save_count = (
            SELECT COUNT(*) FROM question_saves s WHERE s.question_id = questions.id
        )
        """, nativeQuery = true)
    int bulkReconcileSaveCount();

    // ── Full-text search (block-aware) ─────────────────────────────────
    // Per-query block exclusion — pass null/empty for anonymous viewers.
    // No author carve-out on these three: search is a discovery surface, and a
    // question that has not cleared is not discoverable even by the person who
    // wrote it — they reach it from their own profile instead.
    @Query(value = """
        SELECT q.id, ts_rank_cd(to_tsvector('simple',
                  coalesce(q.title,'') || ' ' || coalesce(q.body,'')),
                websearch_to_tsquery('simple', :q)) AS score
        FROM questions q
        WHERE q.deleted_at IS NULL
          AND to_tsvector('simple', coalesce(q.title,'') || ' ' || coalesce(q.body,''))
              @@ websearch_to_tsquery('simple', :q)
          AND (q.moderation_status IS NULL OR q.moderation_status = 'APPROVED')
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR q.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, q.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchFts(@Param("q") String q,
                              @Param("blockedIds") java.util.UUID[] blockedIds,
                              @Param("limit") int limit);

    @Query(value = """
        SELECT q.id, similarity(coalesce(q.title,''), :q) AS score
        FROM questions q
        WHERE q.deleted_at IS NULL
          AND coalesce(q.title,'') % :q
          AND (q.moderation_status IS NULL OR q.moderation_status = 'APPROVED')
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR q.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, q.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchTrgm(@Param("q") String q,
                               @Param("blockedIds") java.util.UUID[] blockedIds,
                               @Param("limit") int limit);

    /** Prefix-only typeahead for the instant search dropdown — block-aware. */
    @Query(value = """
        SELECT q.id,
               CASE WHEN LOWER(q.title) LIKE LOWER(:q || '%') THEN 1.0
                    ELSE similarity(coalesce(q.title,''), :q)
               END AS score
        FROM questions q
        WHERE q.deleted_at IS NULL
          AND (LOWER(q.title) LIKE LOWER(:q || '%')
            OR coalesce(q.title,'') % :q)
          AND (q.moderation_status IS NULL OR q.moderation_status = 'APPROVED')
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR q.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, q.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchPrefix(@Param("q") String q,
                                 @Param("blockedIds") java.util.UUID[] blockedIds,
                                 @Param("limit") int limit);
}