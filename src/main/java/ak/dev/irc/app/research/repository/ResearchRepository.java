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

    @Query("SELECT r FROM Research r WHERE r.slug = :slug AND r.deletedAt IS NULL")
    Optional<Research> findBySlugAndDeletedAtIsNull(@Param("slug") String slug);

    @Query("SELECT r FROM Research r WHERE r.shareToken = :shareToken AND r.deletedAt IS NULL")
    Optional<Research> findByShareTokenAndDeletedAtIsNull(@Param("shareToken") String shareToken);

    @Query("SELECT r FROM Research r WHERE r.id = :id AND r.deletedAt IS NULL")
    Optional<Research> findByIdAndDeletedAtIsNull(@Param("id") UUID id);

    /**
     * Same row, with the author graph fetched. Callers that read
     * {@code researcher.getUsername()} outside a transaction — the admin
     * detail panel — need this: {@code open-in-view} is off, so the lazy
     * proxy is already detached by the time the response is mapped.
     */
    @Query("""
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE r.id = :id AND r.deletedAt IS NULL
        """)
    Optional<Research> findByIdWithResearcher(@Param("id") UUID id);

    // ── Feed queries ─────────────────────────────────────────────────────────

    /**
     * Public research feed. Author + author profile are fetch-joined: the
     * card mapper reads {@code researcher.getProfileImage()}, and without the
     * fetch every card fires a researcher SELECT + a profile SELECT
     * ({@code User.profile} is a non-proxyable mappedBy 1:1). Explicit
     * countQuery — Spring Data cannot derive counts from fetch joins.
     */
    @Query(value = """
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE r.status = :status
          AND r.deletedAt IS NULL
        ORDER BY r.publishedAt DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM Research r
        WHERE r.status = :status AND r.deletedAt IS NULL
        """)
    Page<Research> findByStatusAndDeletedAtIsNullOrderByPublishedAtDesc(
            @Param("status") ResearchStatus status, Pageable pageable);

    /** Block-aware feed: hides researches whose author is in a block edge with the viewer. */
    @Query(value = """
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE r.status = :status
          AND r.deletedAt IS NULL
          AND res.id NOT IN :blockedIds
        ORDER BY r.publishedAt DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM Research r
        WHERE r.status = :status
          AND r.deletedAt IS NULL
          AND r.researcher.id NOT IN :blockedIds
        """)
    Page<Research> findFeedExcluding(@Param("status") ResearchStatus status,
                                     @Param("blockedIds") List<UUID> blockedIds,
                                     Pageable pageable);

    @Query("SELECT r FROM Research r WHERE r.researcher.id = :researcherId AND r.deletedAt IS NULL")
    Page<Research> findByResearcherIdAndDeletedAtIsNull(
            @Param("researcherId") UUID researcherId, Pageable pageable);

    @Query("""
        SELECT r FROM Research r
        WHERE r.researcher.id = :researcherId
          AND r.status = :status
          AND r.deletedAt IS NULL
        """)
    Page<Research> findByResearcherIdAndStatusAndDeletedAtIsNull(
            @Param("researcherId") UUID researcherId, @Param("status") ResearchStatus status,
            Pageable pageable);

    /** Profile-stat count: a researcher's PUBLISHED, non-deleted research. */
    @Query("""
        SELECT COUNT(r) FROM Research r
        WHERE r.researcher.id = :researcherId
          AND r.status = :status
          AND r.deletedAt IS NULL
        """)
    long countByResearcherIdAndStatusAndDeletedAtIsNull(
            @Param("researcherId") UUID researcherId, @Param("status") ResearchStatus status);

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

    // Following feed: published research from followed researchers.
    // Author + profile fetch-joined — same rationale as the public feed above.
    @Query(value = """
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE res.id IN :researcherIds
          AND r.status = 'PUBLISHED'
          AND r.deletedAt IS NULL
        ORDER BY r.publishedAt DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM Research r
        WHERE r.researcher.id IN :researcherIds
          AND r.status = 'PUBLISHED'
          AND r.deletedAt IS NULL
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
    @Query("UPDATE Research r SET r.citationCount = r.citationCount + 1 WHERE r.id = :id")
    void incrementCitationCount(@Param("id") UUID id);

    @Query("SELECT COUNT(r) > 0 FROM Research r WHERE r.slug = :slug")
    boolean existsBySlug(@Param("slug") String slug);

    // ═════════════════════════════════════════════════════════════════════
    //  Admin browse (docs/admin/research-qna.md §4) — all statuses,
    //  soft-deleted included when :deleted says so, fetch-join + countQuery.
    // ═════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE (:status IS NULL OR r.status = :status)
          AND (:researcherId IS NULL OR res.id = :researcherId)
          AND (:ircId IS NULL OR r.ircId = :ircId)
          AND (:q IS NULL OR LOWER(r.title) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
          AND r.deletedAt IS NULL
        ORDER BY r.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM Research r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:researcherId IS NULL OR r.researcher.id = :researcherId)
          AND (:ircId IS NULL OR r.ircId = :ircId)
          AND (:q IS NULL OR LOWER(r.title) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
          AND r.deletedAt IS NULL
        """)
    Page<Research> adminBrowse(@Param("status") ak.dev.irc.app.research.enums.ResearchStatus status,
                               @Param("researcherId") UUID researcherId,
                               @Param("ircId") String ircId,
                               @Param("q") String q,
                               Pageable pageable);

    @Query("SELECT COUNT(r) FROM Research r WHERE r.status = :status AND r.deletedAt IS NULL")
    long countByStatusAndDeletedAtIsNull(
            @Param("status") ak.dev.irc.app.research.enums.ResearchStatus status);

    @Query("SELECT COUNT(r) FROM Research r WHERE r.deletedAt IS NULL")
    long countByDeletedAtIsNull();

    @Query(value = """
            SELECT CAST(date_trunc('day', r.created_at) AS date), COUNT(*)
            FROM researches r
            WHERE r.created_at >= :from AND r.deleted_at IS NULL
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    java.util.List<Object[]> createdPerDay(@Param("from") java.time.LocalDateTime from);

    @Query(value = """
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE r.status = ak.dev.irc.app.research.enums.ResearchStatus.PUBLISHED
          AND r.deletedAt IS NULL
        ORDER BY r.downloadCount DESC, r.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM Research r
        WHERE r.status = ak.dev.irc.app.research.enums.ResearchStatus.PUBLISHED AND r.deletedAt IS NULL
        """)
    Page<Research> topByDownloads(Pageable pageable);

    @Query(value = """
        SELECT r FROM Research r
        JOIN FETCH r.researcher res
        LEFT JOIN FETCH res.profile
        WHERE r.status = ak.dev.irc.app.research.enums.ResearchStatus.PUBLISHED
          AND r.deletedAt IS NULL
        ORDER BY r.citationCount DESC, r.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM Research r
        WHERE r.status = ak.dev.irc.app.research.enums.ResearchStatus.PUBLISHED AND r.deletedAt IS NULL
        """)
    Page<Research> topByCitations(Pageable pageable);
}
