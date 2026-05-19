package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchSaveLookupEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchSaveLookupRepository
        extends CassandraRepository<ResearchSaveLookupEntity, MapId> {

    @Query("SELECT * FROM research_saves_lookup WHERE research_id = :rid AND user_id = :uid")
    Optional<ResearchSaveLookupEntity> find(@Param("rid") UUID researchId, @Param("uid") UUID userId);

    @Query("DELETE FROM research_saves_lookup WHERE research_id = :rid AND user_id = :uid")
    void delete(@Param("rid") UUID researchId, @Param("uid") UUID userId);
}
