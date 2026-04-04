package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchDownload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ResearchDownloadRepository extends JpaRepository<ResearchDownload, UUID> {

    long countByResearchId(UUID researchId);
}
