package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchViewEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// Disambiguated bean name — clashes with the JPA ResearchViewRepository.
@Repository("cassandraResearchViewRepository")
public interface ResearchViewRepository
        extends CassandraRepository<ResearchViewEntity, MapId> {

    @Query("SELECT * FROM research_views_by_research WHERE research_id = :rid AND user_id = :uid")
    Optional<ResearchViewEntity> find(@Param("rid") UUID researchId, @Param("uid") UUID userId);
}
