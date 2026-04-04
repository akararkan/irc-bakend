package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchReaction;
import ak.dev.irc.app.research.entity.ResearchReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
