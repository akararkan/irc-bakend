package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchDownload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ResearchDownloadRepository extends JpaRepository<ResearchDownload, UUID> {

    long countByResearchId(UUID researchId);

    /** Cascade purge — used when the parent research is hard-deleted. */
    @Modifying
    @Query("DELETE FROM ResearchDownload d WHERE d.research.id = :researchId")
    int deleteAllByResearchId(@Param("researchId") UUID researchId);
}
