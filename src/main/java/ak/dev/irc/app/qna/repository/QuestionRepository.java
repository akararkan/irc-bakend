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

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    Page<Question> findByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    // Cursor-paginated feed — O(log n) deep paging that does not degrade as
    // the user scrolls. Split into two methods so :cursor has a concrete type
    // bound on Postgres.
    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedFirstPage(Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
          AND q.createdAt < :cursor
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedAfter(@Param("cursor") java.time.LocalDateTime cursor, Pageable pageable);

    // ── Block-aware feed variants ────────────────────────────────────
    // Drop questions whose author is in any block-relationship with the
    // viewer in the same query — no per-row scan, single index hit.

    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
          AND q.author.id NOT IN :blockedIds
        ORDER BY q.createdAt DESC
        """)
    Page<Question> findFeedExcluding(@Param("blockedIds") List<UUID> blockedIds, Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
          AND q.author.id NOT IN :blockedIds
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedFirstPageExcluding(@Param("blockedIds") List<UUID> blockedIds, Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
          AND q.createdAt < :cursor
          AND q.author.id NOT IN :blockedIds
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedAfterExcluding(@Param("cursor") java.time.LocalDateTime cursor,
                                          @Param("blockedIds") List<UUID> blockedIds,
                                          Pageable pageable);

    // Following feed: questions from followed users
    @Query("""
        SELECT q FROM Question q
        WHERE q.author.id IN :authorIds
          AND q.deletedAt IS NULL
        ORDER BY q.createdAt DESC
        """)
    Page<Question> findFollowingFeed(@Param("authorIds") List<UUID> authorIds, Pageable pageable);

    Page<Question> findByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

    Optional<Question> findByIdAndDeletedAtIsNull(UUID id);

    /** Atomic view-count bump — runs in its own write tx so the read path can stay readOnly. */
    @Modifying
    @Query("UPDATE Question q SET q.viewCount = q.viewCount + 1 WHERE q.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    // ── Full-text search (block-aware) ─────────────────────────────────
    // Per-query block exclusion — pass null/empty for anonymous viewers.
    @Query(value = """
        SELECT q.id, ts_rank_cd(to_tsvector('simple',
                  coalesce(q.title,'') || ' ' || coalesce(q.body,'')),
                websearch_to_tsquery('simple', :q)) AS score
        FROM questions q
        WHERE q.deleted_at IS NULL
          AND to_tsvector('simple', coalesce(q.title,'') || ' ' || coalesce(q.body,''))
              @@ websearch_to_tsquery('simple', :q)
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
          AND coalesce(q.title,'') %% :q
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
            OR coalesce(q.title,'') %% :q)
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR q.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, q.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchPrefix(@Param("q") String q,
                                 @Param("blockedIds") java.util.UUID[] blockedIds,
                                 @Param("limit") int limit);
}