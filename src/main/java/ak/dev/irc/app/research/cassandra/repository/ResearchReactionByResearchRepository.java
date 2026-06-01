package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchReactionByResearchEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchReactionByResearchRepository
        extends CassandraRepository<ResearchReactionByResearchEntity, MapId> {

    @Query("SELECT * FROM research_reactions_by_research WHERE research_id = :rid AND user_id = :uid")
    Optional<ResearchReactionByResearchEntity> find(@Param("rid") UUID researchId,
                                                    @Param("uid") UUID userId);

    @Query("DELETE FROM research_reactions_by_research WHERE research_id = :rid AND user_id = :uid")
    void delete(@Param("rid") UUID researchId, @Param("uid") UUID userId);

    /** Partition-level delete — wipes every reactor row for a research. */
    @Query("DELETE FROM research_reactions_by_research WHERE research_id = :rid")
    void deleteAllForResearch(@Param("rid") UUID researchId);
}
