package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.enums.ResearchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchRepository extends JpaRepository<Research, UUID> {

    Optional<Research> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Research> findByShareTokenAndDeletedAtIsNull(String shareToken);

    Optional<Research> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Research> findByIrcIdAndDeletedAtIsNull(String ircId);

    // ── Feed queries ─────────────────────────────────────────────────────────

    Page<Research> findByStatusAndDeletedAtIsNullOrderByPublishedAtDesc(
            ResearchStatus status, Pageable pageable);

    /** Block-aware feed: hides researches whose author is in a block edge with the viewer. */
    @Query("""
        SELECT r FROM Research r
        WHERE r.status = :status
          AND r.deletedAt IS NULL
          AND r.researcher.id NOT IN :blockedIds
        ORDER BY r.publishedAt DESC
        """)
    Page<Research> findFeedExcluding(@Param("status") ResearchStatus status,
                                     @Param("blockedIds") List<UUID> blockedIds,
                                     Pageable pageable);

    Page<Research> findByResearcherIdAndDeletedAtIsNull(UUID researcherId, Pageable pageable);

    Page<Research> findByResearcherIdAndStatusAndDeletedAtIsNull(
            UUID researcherId, ResearchStatus status, Pageable pageable);

    /** Profile-stat count: a researcher's PUBLISHED, non-deleted research. */
    long countByResearcherIdAndStatusAndDeletedAtIsNull(UUID researcherId, ResearchStatus status);

    /**
     * Drafts whose scheduled publish time has arrived — driven by the
     * scheduled-publish job. {@code JOIN FETCH} the researcher so the job can read
     * the owner id outside a session without a lazy-init error.
     */
    @Query("""
        SELECT r FROM Research r JOIN FETCH r.researcher
        WHERE r.status = :status
          AND r.deletedAt IS NULL
          AND r.scheduledPublishAt IS NOT NULL
          AND r.scheduledPublishAt <= :now
        """)
    List<Research> findDueForScheduledPublish(@Param("status") ResearchStatus status,
                                              @Param("now") LocalDateTime now);

    // Following feed: published research from followed researchers
    @Query("""
        SELECT r FROM Research r
        WHERE r.researcher.id IN :researcherIds
          AND r.status = 'PUBLISHED'
          AND r.deletedAt IS NULL
        ORDER BY r.publishedAt DESC
        """)
    Page<Research> findFollowingFeed(@Param("researcherIds") List<UUID> researcherIds, Pageable pageable);

    // ── LIKE search ──────────────────────────────────────────────────────────

    @Query("""
        SELECT r FROM Research r
        WHERE r.deletedAt IS NULL
          AND r.status = 'PUBLISHED'
          AND (LOWER(r.title) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(r.keywords) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(r.abstractText) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY r.publishedAt DESC
    """)
    Page<Research> searchPublished(@Param("q") String query, Pageable pageable);

    // ── Full-text search (PostgreSQL GIN index) ───────────────────────────────

    @Query(value = """
        SELECT * FROM researches
        WHERE deleted_at IS NULL
          AND status = 'PUBLISHED'
          AND search_vector @@ to_tsquery('english', :tsQuery)
        ORDER BY ts_rank(search_vector, to_tsquery('english', :tsQuery)) DESC
    """, countQuery = """
        SELECT count(*) FROM researches
        WHERE deleted_at IS NULL
          AND status = 'PUBLISHED'
          AND search_vector @@ to_tsquery('english', :tsQuery)
    """, nativeQuery = true)
    Page<Research> fullTextSearch(@Param("tsQuery") String tsQuery, Pageable pageable);

    // ── Tag search ───────────────────────────────────────────────────────────

    @Query("""
        SELECT DISTINCT r FROM Research r
        JOIN r.tags t
        WHERE r.deletedAt IS NULL
          AND r.status = 'PUBLISHED'
          AND t.tagName IN :tagNames
        ORDER BY r.publishedAt DESC
    """)
    Page<Research> findByTags(@Param("tagNames") List<String> tagNames, Pageable pageable);

    // ── Scheduled publish ────────────────────────────────────────────────────

    List<Research> findByStatusAndScheduledPublishAtBeforeAndDeletedAtIsNull(
            ResearchStatus status, LocalDateTime now);

    // ── Counter adjustments ───────────────────────────────────────────────────

    @Modifying
    @Query("UPDATE Research r SET r.viewCount = r.viewCount + 1 WHERE r.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Research r SET r.downloadCount = r.downloadCount + 1 WHERE r.id = :id")
    void incrementDownloadCount(@Param("id") UUID id);

    // Clamp at zero — concurrent decrements (rapid react/un-react, comment
    // race) must never push the counter negative.
    @Modifying
    @Query("UPDATE Research r SET r.reactionCount = " +
            "CASE WHEN r.reactionCount + :delta < 0 THEN 0 ELSE r.reactionCount + :delta END " +
            "WHERE r.id = :id")
    void adjustReactionCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Research r SET r.commentCount = " +
            "CASE WHEN r.commentCount + :delta < 0 THEN 0 ELSE r.commentCount + :delta END " +
            "WHERE r.id = :id")
    void adjustCommentCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Research r SET r.saveCount = " +
            "CASE WHEN r.saveCount + :delta < 0 THEN 0 ELSE r.saveCount + :delta END " +
            "WHERE r.id = :id")
    void adjustSaveCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Research r SET r.shareCount = r.shareCount + 1 WHERE r.id = :id")
    void incrementShareCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Research r SET r.shareCount = " +
            "CASE WHEN r.shareCount > 0 THEN r.shareCount - 1 ELSE 0 END WHERE r.id = :id")
    void decrementShareCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Research r SET r.citationCount = r.citationCount + 1 WHERE r.id = :id")
    void incrementCitationCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Research r SET r.citationCount = " +
            "CASE WHEN r.citationCount > 0 THEN r.citationCount - 1 ELSE 0 END WHERE r.id = :id")
    void decrementCitationCount(@Param("id") UUID id);

    // ── Bulk reconcile from source-of-truth row counts ──────────────────────

    @Modifying
    @Query(value = """
        UPDATE researches SET reaction_count = (
            SELECT COUNT(*) FROM research_reactions r WHERE r.research_id = researches.id
        )
        """, nativeQuery = true)
    int bulkReconcileReactionCount();

    @Modifying
    @Query(value = """
        UPDATE researches SET comment_count = (
            SELECT COUNT(*) FROM research_comments c
            WHERE c.research_id = researches.id AND c.deleted_at IS NULL
        )
        """, nativeQuery = true)
    int bulkReconcileCommentCount();

    @Modifying
    @Query(value = """
        UPDATE researches SET save_count = (
            SELECT COUNT(*) FROM research_saves s WHERE s.research_id = researches.id
        )
        """, nativeQuery = true)
    int bulkReconcileSaveCount();

    @Modifying
    @Query(value = """
        UPDATE researches SET download_count = (
            SELECT COUNT(*) FROM research_downloads d WHERE d.research_id = researches.id
        )
        """, nativeQuery = true)
    int bulkReconcileDownloadCount();

    boolean existsBySlug(String slug);

    // ── Full-text search (Postgres FTS + trigram fallback) ─────────────
    // Indexed by SearchInfrastructureInitializer; sub-100ms on millions of rows.
    // Block-aware via :blockedIds — pass null/empty for anonymous viewers.
    /**
     * FTS search with engagement boost — the BM25-style {@code ts_rank_cd} score
     * is multiplied by {@code ln(1 + reactions + views/10 + downloads*5 + citations*10)}
     * so popular research surfaces above bare keyword matches with equal text
     * relevance. Downloads and citations weighted more because they signal
     * deeper scholarly intent than a view.
     */
    @Query(value = """
        SELECT r.id, (ts_rank_cd(to_tsvector('simple',
                  coalesce(r.title, '') || ' ' || coalesce(r.abstract_text, '') || ' ' ||
                  coalesce(r.description, '') || ' ' || coalesce(r.keywords, '')),
                websearch_to_tsquery('simple', :q))
              * (1 + LN(1 + r.reaction_count
                          + r.view_count / 10.0
                          + r.download_count * 5
                          + r.citation_count * 10))) AS score
        FROM researches r
        WHERE r.deleted_at IS NULL
          AND r.status = 'PUBLISHED'
          AND to_tsvector('simple',
                  coalesce(r.title, '') || ' ' || coalesce(r.abstract_text, '') || ' ' ||
                  coalesce(r.description, '') || ' ' || coalesce(r.keywords, ''))
              @@ websearch_to_tsquery('simple', :q)
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR r.researcher_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, r.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchFts(@Param("q") String q,
                              @Param("blockedIds") java.util.UUID[] blockedIds,
                              @Param("limit") int limit);

    @Query(value = """
        SELECT r.id, GREATEST(similarity(coalesce(r.title,''), :q),
                              similarity(coalesce(r.keywords,''), :q)) AS score
        FROM researches r
        WHERE r.deleted_at IS NULL
          AND r.status = 'PUBLISHED'
          AND (coalesce(r.title,'') % :q OR coalesce(r.keywords,'') % :q)
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR r.researcher_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, r.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchTrgm(@Param("q") String q,
                               @Param("blockedIds") java.util.UUID[] blockedIds,
                               @Param("limit") int limit);

    /** Prefix-only typeahead for the instant search dropdown — block-aware. */
    @Query(value = """
        SELECT r.id,
               CASE WHEN LOWER(r.title) LIKE LOWER(:q || '%') THEN 1.0
                    WHEN LOWER(r.keywords) LIKE LOWER(:q || '%') THEN 0.8
                    ELSE GREATEST(similarity(coalesce(r.title,''), :q),
                                  similarity(coalesce(r.keywords,''), :q))
               END AS score
        FROM researches r
        WHERE r.deleted_at IS NULL
          AND r.status = 'PUBLISHED'
          AND (LOWER(r.title) LIKE LOWER(:q || '%')
            OR LOWER(r.keywords) LIKE LOWER(:q || '%')
            OR coalesce(r.title,'') % :q)
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR r.researcher_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, r.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchPrefix(@Param("q") String q,
                                 @Param("blockedIds") java.util.UUID[] blockedIds,
                                 @Param("limit") int limit);
}
