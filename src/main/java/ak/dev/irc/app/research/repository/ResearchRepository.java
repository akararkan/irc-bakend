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

    Page<Research> findByResearcherIdAndDeletedAtIsNull(UUID researcherId, Pageable pageable);

    Page<Research> findByResearcherIdAndStatusAndDeletedAtIsNull(
            UUID researcherId, ResearchStatus status, Pageable pageable);

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

    @Modifying
    @Query("UPDATE Research r SET r.reactionCount = r.reactionCount + :delta WHERE r.id = :id")
    void adjustReactionCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Research r SET r.commentCount = r.commentCount + :delta WHERE r.id = :id")
    void adjustCommentCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Research r SET r.saveCount = r.saveCount + :delta WHERE r.id = :id")
    void adjustSaveCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Research r SET r.shareCount = r.shareCount + 1 WHERE r.id = :id")
    void incrementShareCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Research r SET r.citationCount = r.citationCount + 1 WHERE r.id = :id")
    void incrementCitationCount(@Param("id") UUID id);

    boolean existsBySlug(String slug);
}
