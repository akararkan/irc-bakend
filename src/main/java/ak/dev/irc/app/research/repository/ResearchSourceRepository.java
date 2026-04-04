package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchSourceRepository extends JpaRepository<ResearchSource, UUID> {

    List<ResearchSource> findByResearchIdOrderByDisplayOrderAsc(UUID researchId);

    void deleteAllByResearchId(UUID researchId);
}
