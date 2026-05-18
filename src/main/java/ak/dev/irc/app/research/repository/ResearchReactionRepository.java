package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchReaction;
import ak.dev.irc.app.research.entity.ResearchReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ResearchReactionRepository extends JpaRepository<ResearchReaction, ResearchReactionId> {

    boolean existsById(ResearchReactionId id);

    /** Reaction breakdown for a research: [ReactionType, count] */
    @Query("""
        SELECT r.reactionType, COUNT(r)
        FROM ResearchReaction r
        WHERE r.research.id = :researchId
        GROUP BY r.reactionType
    """)
    List<Object[]> countByResearchGroupedByType(@Param("researchId") UUID researchId);

    long countByResearchId(UUID researchId);

    /**
     * Batch lookup so feed renders can mark {@code currentUserReacted} per
     * research without N+1 round trips over the lazy {@code r.getReactions()}
     * collection. Returns the set of research IDs in {@code researchIds} that
     * the viewer has reacted to. Single LIKE reaction model — no per-type
     * distinction needed.
     */
    @Query("""
        SELECT r.research.id FROM ResearchReaction r
        WHERE r.user.id = :userId AND r.research.id IN :researchIds
    """)
    Set<UUID> findReactedResearchIds(@Param("userId") UUID userId,
                                     @Param("researchIds") List<UUID> researchIds);
}
