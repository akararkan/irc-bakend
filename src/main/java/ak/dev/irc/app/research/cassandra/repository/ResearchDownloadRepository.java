package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchDownloadEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// Disambiguated bean name — clashes with the JPA ResearchDownloadRepository.
@Repository("cassandraResearchDownloadRepository")
public interface ResearchDownloadRepository
        extends CassandraRepository<ResearchDownloadEntity, MapId> {

    @Query("SELECT * FROM research_downloads_by_research WHERE research_id = :rid LIMIT :pageSize")
    List<ResearchDownloadEntity> recent(@Param("rid") UUID researchId,
                                        @Param("pageSize") int pageSize);

    /** Partition-level delete — wipes every download row for a research. */
    @Query("DELETE FROM research_downloads_by_research WHERE research_id = :rid")
    void deleteAllForResearch(@Param("rid") UUID researchId);
}
