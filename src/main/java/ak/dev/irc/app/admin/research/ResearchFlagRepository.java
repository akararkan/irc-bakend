package ak.dev.irc.app.admin.research;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResearchFlagRepository extends JpaRepository<ResearchFlag, UUID> {

    List<ResearchFlag> findByResearchIdOrderByCreatedAtDesc(UUID researchId);

    Page<ResearchFlag> findByResolvedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
