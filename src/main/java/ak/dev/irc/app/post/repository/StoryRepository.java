package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.Story;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface StoryRepository extends JpaRepository<Story, UUID> {

    /** Active stories for a specific author — visibility filtered on caller side. */
    @Query("""
        SELECT s FROM Story s
        WHERE s.author.id = :authorId
          AND s.isDeleted = FALSE
          AND s.expiresAt > :now
        ORDER BY s.createdAt ASC
        """)
    List<Story> findActiveByAuthorId(@Param("authorId") UUID authorId,
                                     @Param("now") LocalDateTime now);

    /**
     * Story tray: authors who have active stories, sorted by whether the viewer
     * has unseen stories. Returns author IDs — service groups and sorts them.
     */
    @Query(value = """
        SELECT DISTINCT s.author_id
        FROM stories s
        WHERE s.is_deleted = FALSE
          AND s.expires_at > :now
          AND s.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY')
          AND s.author_id IN :followedIds
        """, nativeQuery = true)
    List<UUID> findAuthorIdsWithActiveStories(@Param("followedIds") Collection<UUID> followedIds,
                                              @Param("now") LocalDateTime now);

    @Query("""
        SELECT s FROM Story s
        WHERE s.highlight.id = :highlightId
        ORDER BY s.createdAt ASC
        """)
    Page<Story> findByHighlightId(@Param("highlightId") UUID highlightId, Pageable pageable);

    /** Soft-delete expired stories that are not in a highlight. */
    @Modifying
    @Query("""
        UPDATE Story s
        SET s.isDeleted = TRUE, s.deletedAt = :now
        WHERE s.isDeleted = FALSE
          AND s.expiresAt < :now
          AND s.highlight IS NULL
        """)
    int softDeleteExpired(@Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE Story s SET s.viewCount = s.viewCount + 1 WHERE s.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Story s SET s.reactionCount = s.reactionCount + 1 WHERE s.id = :id")
    void incrementReactionCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Story s SET s.replyCount = s.replyCount + 1 WHERE s.id = :id")
    void incrementReplyCount(@Param("id") UUID id);
}
