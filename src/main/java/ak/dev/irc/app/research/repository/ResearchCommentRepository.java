package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ResearchCommentRepository extends JpaRepository<ResearchComment, UUID> {

    /** Top-level comments for a research (parent IS NULL) */
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
}
