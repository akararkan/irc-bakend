package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.StoryView;
import ak.dev.irc.app.post.enums.ViewerRelationship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface StoryViewRepository extends JpaRepository<StoryView, StoryView.StoryViewId> {

    boolean existsByIdStoryIdAndIdViewerId(UUID storyId, UUID viewerId);

    Optional<StoryView> findByIdStoryIdAndIdViewerId(UUID storyId, UUID viewerId);

    /** IDs of stories (from a set) that the viewer has already seen. */
    @Query("""
        SELECT sv.id.storyId FROM StoryView sv
        WHERE sv.id.viewerId = :viewerId
          AND sv.id.storyId IN :storyIds
        """)
    Set<UUID> findSeenStoryIds(@Param("viewerId") UUID viewerId,
                               @Param("storyIds") Collection<UUID> storyIds);

    /** Viewers of a story — newest first — for the author's viewer list. */
    @Query("""
        SELECT sv FROM StoryView sv
        WHERE sv.id.storyId = :storyId
        ORDER BY sv.viewedAt DESC
        """)
    Page<StoryView> findByStoryId(@Param("storyId") UUID storyId, Pageable pageable);

    // ── View count breakdown by relationship ─────────────────────────────────

    @Query("""
        SELECT sv.viewerRelationship, COUNT(sv)
        FROM StoryView sv
        WHERE sv.id.storyId = :storyId
        GROUP BY sv.viewerRelationship
        """)
    List<Object[]> countGroupedByRelationship(@Param("storyId") UUID storyId);

    @Query("""
        SELECT COUNT(sv) FROM StoryView sv
        WHERE sv.id.storyId = :storyId
          AND sv.viewerRelationship = :rel
        """)
    long countByStoryAndRelationship(@Param("storyId") UUID storyId,
                                     @Param("rel") ViewerRelationship rel);

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Modifying
    @Query("""
        UPDATE StoryView sv
        SET sv.reactionEmoji = :emoji
        WHERE sv.id.storyId = :storyId AND sv.id.viewerId = :viewerId
        """)
    int updateReaction(@Param("storyId") UUID storyId,
                       @Param("viewerId") UUID viewerId,
                       @Param("emoji") String emoji);
}
