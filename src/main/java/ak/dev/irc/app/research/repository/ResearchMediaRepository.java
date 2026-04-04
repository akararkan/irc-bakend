package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchMediaRepository extends JpaRepository<ResearchMedia, UUID> {

    List<ResearchMedia> findByResearchIdOrderByDisplayOrderAsc(UUID researchId);

    void deleteAllByResearchId(UUID researchId);
}
